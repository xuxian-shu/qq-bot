package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.notice.GroupIncreaseNoticeEvent;
import com.qqbot.service.PointsService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 新人入群欢迎 + 破冰奖励。
 * <p>
 * 入群：欢迎、建档、送 10 初始积分，并给这个新人打一个 24 小时的破冰标记。
 * 破冰：标记有效期内，发一条不少于 10 个字的非指令消息（例如自我介绍），
 * 原子消费掉标记后 +10 积分，保证只能领一次。
 */
@Component
public class WelcomeHandler extends BotPlugin {

    private static final Logger log = LoggerFactory.getLogger(WelcomeHandler.class);
    private static final String INTRO_KEY_PREFIX = "welcome:intro:";
    // 去掉 CQ 码，免得把 @、图片 URL 里的字符算进自我介绍长度
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^]]*]");

    @Resource
    private PointsService pointsService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${welcome.enabled:true}")
    private boolean enabled;

    @Value("${welcome.initial-points:10}")
    private int initialPoints;

    @Value("${welcome.intro-points:10}")
    private int introPoints;

    @Value("${welcome.intro-hours:24}")
    private long introHours;

    @Value("${welcome.intro-min-length:10}")
    private int introMinLength;

    /**
     * 新人入群事件（NapCat 的 notice.group_increase）
     */
    @Override
    public int onGroupIncreaseNotice(Bot bot, GroupIncreaseNoticeEvent event) {
        if (!enabled) {
            return MESSAGE_IGNORE;
        }
        Long groupId = event.getGroupId();
        Long userId = event.getUserId();
        if (groupId == null || userId == null) {
            return MESSAGE_IGNORE;
        }
        // 机器人自己被拉进群时也会触发，跳过
        if (event.getSelfId() != null && event.getSelfId().equals(userId)) {
            return MESSAGE_IGNORE;
        }

        String nickname = getNickname(bot, groupId, userId);
        boolean newUser = false;
        try {
            // 只有人生第一次建档才送分，退群再进不会重复领
            newUser = pointsService.registerIfAbsent(userId, nickname, initialPoints);
        } catch (Exception e) {
            log.error("新人建档失败：group={}, qq={}", groupId, userId, e);
        }

        // 只有首次建档的新人才能参与破冰奖励，防止反复退群薅积分
        if (newUser) {
            try {
                stringRedisTemplate.opsForValue().set(
                        introKey(groupId, userId), "1", introHours, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("破冰标记写入失败，本次不发破冰奖励：group={}, qq={}", groupId, userId, e);
            }
        }

        String message = "欢迎 [CQ:at,qq=" + userId + "] 加入本群喵～\n"
                + (newUser ? "已为你建档并赠送 " + initialPoints + " 初始积分；\n" : "")
                + "请先看看群公告；入群 " + introHours + " 小时内发一句自我介绍（不少于 "
                + introMinLength + " 个字），还能再拿 " + introPoints + " 积分哦！";
        bot.sendGroupMsg(groupId, message, false);
        return MESSAGE_IGNORE;
    }

    /**
     * 观察群消息，命中破冰条件就发奖励。
     * 不拦截消息，返回 MESSAGE_IGNORE 让其他 Handler 继续处理。
     */
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        if (!enabled) {
            return MESSAGE_IGNORE;
        }
        Long groupId = event.getGroupId();
        Long userId = event.getUserId();
        if (groupId == null || userId == null) {
            return MESSAGE_IGNORE;
        }

        // 先做轻量判断（长度、指令），大部分消息不会碰 Redis
        if (!looksLikeIntro(event.getMessage())) {
            return MESSAGE_IGNORE;
        }

        String key = introKey(groupId, userId);
        try {
            // getAndDelete 是原子的，并发两条消息只有一条能拿到奖励
            if (stringRedisTemplate.opsForValue().getAndDelete(key) == null) {
                return MESSAGE_IGNORE;
            }
        } catch (Exception e) {
            log.warn("破冰标记读取失败，本次不发奖励：group={}, qq={}", groupId, userId, e);
            return MESSAGE_IGNORE;
        }

        try {
            pointsService.addPoints(userId, introPoints);
            bot.sendGroupMsg(groupId,
                    "[CQ:at,qq=" + userId + "] 自我介绍成功，破冰 +" + introPoints
                            + " 积分！欢迎融入本群喵～", false);
        } catch (Exception e) {
            log.error("破冰奖励发放失败：group={}, qq={}", groupId, userId, e);
        }
        return MESSAGE_IGNORE;
    }

    private String introKey(long groupId, long userId) {
        return INTRO_KEY_PREFIX + groupId + ":" + userId;
    }

    /**
     * 判断像不像自我介绍：去掉 CQ 码和首尾空格后，
     * 不是已知指令，且可见字母/数字不少于阈值。
     */
    private boolean looksLikeIntro(String message) {
        // 先用原始长度挡掉大部分短消息，避免每条群消息都做正则替换
        if (message == null || message.length() < introMinLength) {
            return false;
        }
        String text = CQ_CODE.matcher(message).replaceAll("").trim();
        if (text.isEmpty() || isCommand(text)) {
            return false;
        }
        return visibleLength(text) >= introMinLength;
    }

    /**
     * 这些是指令类消息，不能拿来当自我介绍领奖励
     */
    private boolean isCommand(String text) {
        return "签到".equals(text) || "hello".equalsIgnoreCase(text)
                || "积分".equals(text) || "积分排行".equals(text)
                || text.startsWith("点歌")
                || text.startsWith("提醒我")
                || "天气".equals(text) || text.startsWith("天气 ")
                || text.startsWith("答题") || "今日一题".equals(text)
                || "今日运势".equals(text) || "运势".equals(text) || "求签".equals(text)
                || "帮助".equals(text) || "指南".equals(text) || "菜单".equals(text)
                || BiliHandler.isBiliCommand(text);
    }

    /** 只统计字母和数字（中文算字母），排除纯表情、标点、空白 */
    private int visibleLength(String text) {
        return (int) text.codePoints()
                .filter(Character::isLetterOrDigit)
                .count();
    }

    private String getNickname(Bot bot, long groupId, long userId) {
        try {
            var resp = bot.getGroupMemberInfo(groupId, userId, true);
            if (resp != null && resp.getData() != null) {
                String card = resp.getData().getCard();
                if (card != null && !card.isBlank()) {
                    return card;
                }
                String nickname = resp.getData().getNickname();
                if (nickname != null && !nickname.isBlank()) {
                    return nickname;
                }
            }
        } catch (Exception e) {
            log.warn("获取新人昵称失败：group={}, qq={}", groupId, userId, e);
        }
        return "新同学";
    }
}