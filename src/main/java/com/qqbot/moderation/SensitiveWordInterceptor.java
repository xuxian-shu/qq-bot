package com.qqbot.moderation;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotMessageEventInterceptor;
import com.mikuac.shiro.dto.action.common.ActionRaw;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.MessageEvent;
import com.qqbot.service.PointsService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 群消息风控：在所有插件分发之前拦截，命中敏感词就撤回、扣分、按次数禁言。
 * <p>
 * 注册方式：application.yml 里配置 shiro.interceptor 指向本类。
 * preHandle 返回 false 表示拦截，后续 BotPlugin（包括 AI）都不会收到这条消息。
 * <p>
 * 只处理群聊；私聊直接放行。风控自身异常时选择“放行”，避免组件故障导致机器人整体不响应。
 */
@Component
public class SensitiveWordInterceptor implements BotMessageEventInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordInterceptor.class);
    private static final String HIT_KEY_PREFIX = "moderation:hit:";

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @Resource
    private PointsService pointsService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${moderation.enabled:true}")
    private boolean enabled;

    @Value("${moderation.deduct-points:5}")
    private int deductPoints;

    @Value("${moderation.mute-seconds:600}")
    private int muteSeconds;

    @Value("${moderation.long-mute-seconds:3600}")
    private int longMuteSeconds;

    @Value("${moderation.offense-window-hours:24}")
    private long offenseWindowHours;

    @Value("${moderation.exempt-admins:true}")
    private boolean exemptAdmins;

    @Override
    public boolean preHandle(Bot bot, MessageEvent event) {
        if (!enabled || !(event instanceof GroupMessageEvent groupEvent)) {
            return true;
        }

        Long groupId = groupEvent.getGroupId();
        Long userId = groupEvent.getUserId();
        String message = groupEvent.getMessage();
        if (groupId == null || userId == null || message == null || message.isBlank()) {
            return true;
        }
        // 机器人自己发的消息不处理，避免自己撤回自己
        if (groupEvent.getSelfId() != null && groupEvent.getSelfId().equals(userId)) {
            return true;
        }

        String hitWord;
        try {
            hitWord = sensitiveWordFilter.findHit(message);
        } catch (Exception e) {
            // 匹配环节出问题就放行，风控不能把正常聊天拖死
            log.error("敏感词匹配失败，放行本条消息：group={}, qq={}", groupId, userId, e);
            return true;
        }
        if (hitWord == null) {
            return true;
        }

        if (exemptAdmins && isAdmin(bot, groupId, userId)) {
            log.info("敏感词命中但用户是管理员，跳过处置：group={}, qq={}", groupId, userId);
            return true;
        }

        try {
            punish(bot, groupId, userId, groupEvent.getMessageId(), hitWord);
        } catch (Exception e) {
            // 处置失败也要拦截，不能让违规消息继续流向 AI 等插件
            log.error("敏感词处置失败：group={}, qq={}", groupId, userId, e);
        }
        return false;
    }

    @Override
    public void afterCompletion(Bot bot, MessageEvent event) {
        // 目前无收尾工作
    }

    private void punish(Bot bot, long groupId, long userId, Integer messageId, String hitWord) {
        boolean recalled = recall(bot, messageId);

        try {
            pointsService.deductPoints(userId, deductPoints);
        } catch (Exception e) {
            log.warn("违规扣分失败：qq={}", userId, e);
        }

        long hitCount = increaseHitCount(groupId, userId);
        String at = "[CQ:at,qq=" + userId + "]";

        if (hitCount <= 1) {
            String action = recalled ? "已撤回并扣除 " + deductPoints + " 积分" : "已记录并扣除 " + deductPoints + " 积分";
            sendGroupMsg(bot, groupId, at + " " + action + "，请注意群规喵～");
        } else {
            int seconds = hitCount == 2 ? muteSeconds : longMuteSeconds;
            boolean muted = seconds > 0 && ban(bot, groupId, userId, seconds);

            if (muted) {
                sendGroupMsg(bot, groupId, at + " 多次违规，已禁言 " + (seconds / 60) + " 分钟，请注意群规喵～");
            } else {
                sendGroupMsg(bot, groupId, at + " 多次违规，已记录。机器人没有禁言权限，请管理员处理喵～");
            }
        }

        // 只记录命中词和处置结果，不落库原始消息内容
        log.warn("命中敏感词：group={}, qq={}, word={}, recalled={}, hitCount={}",
                groupId, userId, hitWord, recalled, hitCount);
    }

    private boolean recall(Bot bot, Integer messageId) {
        if (messageId == null) {
            return false;
        }
        try {
            ActionRaw resp = bot.deleteMsg(messageId);
            if (resp == null || resp.getRetCode() == null || resp.getRetCode() != 0) {
                log.warn("撤回失败（机器人可能不是管理员或已超过撤回时限）：messageId={}, resp={}",
                        messageId, resp);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("撤回异常：messageId={}", messageId, e);
            return false;
        }
    }

    private boolean ban(Bot bot, long groupId, long userId, int seconds) {
        try {
            ActionRaw resp = bot.setGroupBan(groupId, userId, seconds);
            if (resp == null || resp.getRetCode() == null || resp.getRetCode() != 0) {
                log.warn("禁言失败（机器人可能不是管理员）：group={}, qq={}, resp={}",
                        groupId, userId, resp);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("禁言异常：group={}, qq={}", groupId, userId, e);
            return false;
        }
    }

    private void sendGroupMsg(Bot bot, long groupId, String message) {
        try {
            bot.sendGroupMsg(groupId, message, false);
        } catch (Exception e) {
            log.warn("风控提示发送失败：group={}", groupId, e);
        }
    }

    /**
     * 24 小时内的违规次数。Redis 不可用时按 1 次处理：
     * 只撤回+扣分，不做禁言，宁可少罚也不误封。
     */
    private long increaseHitCount(long groupId, long userId) {
        String key = HIT_KEY_PREFIX + groupId + ":" + userId;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, offenseWindowHours, TimeUnit.HOURS);
            return count == null ? 1 : count;
        } catch (Exception e) {
            log.warn("违规计数失败，按首次处理：group={}, qq={}", groupId, userId, e);
            return 1;
        }
    }

    /** 群主/管理员豁免，避免误伤自己人 */
    private boolean isAdmin(Bot bot, long groupId, long userId) {
        try {
            var resp = bot.getGroupMemberInfo(groupId, userId, true);
            if (resp == null || resp.getData() == null) {
                return false;
            }
            String role = resp.getData().getRole();
            return "owner".equals(role) || "admin".equals(role);
        } catch (Exception e) {
            log.warn("查询群成员身份失败，按普通成员处理：group={}, qq={}", groupId, userId, e);
            return false;
        }
    }
}