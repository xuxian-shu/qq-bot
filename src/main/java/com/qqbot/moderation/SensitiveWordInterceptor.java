package com.qqbot.moderation;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotMessageEventInterceptor;
import com.mikuac.shiro.dto.action.common.ActionRaw;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.MessageEvent;
import com.qqbot.service.ChatActivityService;
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
 * <p>
 * 除风控外还承担两个全局职责（因为这是插件分发前唯一的统一入口）：
 * ①机器人自身消息在这里直接 return false 阻断——主动发言（冷场唤醒/话题接茬）
 * 的防死循环不能靠各插件自觉，必须掐在源头；
 * ②人类群消息的活跃度记录（时间戳+聊天日志），供冷场唤醒等功能消费。
 */
@Component
public class SensitiveWordInterceptor implements BotMessageEventInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordInterceptor.class);
    private static final String HIT_KEY_PREFIX = "moderation:hit:";
    private static final String ANNOUNCE_KEY_PREFIX = "moderation:announce:";

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @Resource
    private PointsService pointsService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ChatActivityService chatActivityService;

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

    /** 处罚公告频控：同群两条公告最短间隔秒数 */
    @Value("${moderation.announce-cooldown-seconds:300}")
    private long announceCooldownSeconds;

    @Override
    public boolean preHandle(Bot bot, MessageEvent event) {
        if (!(event instanceof GroupMessageEvent groupEvent)) {
            return true;
        }

        Long groupId = groupEvent.getGroupId();
        Long userId = groupEvent.getUserId();
        String message = groupEvent.getMessage();

        // 防"自言自语"死循环第一铁律：机器人自己的消息在分发入口直接阻断，
        // 任何插件都不会收到它。冷场唤醒/话题接茬主动发出的消息若回流再触发
        // 处理器，就会无限循环刷屏。这里 return false 是一劳永逸的源头防线。
        if (groupEvent.getSelfId() != null && groupEvent.getSelfId().equals(userId)) {
            return false;
        }

        if (groupId == null || userId == null || message == null || message.isBlank()) {
            return true;
        }

        // 活跃度记录：每条人类群消息刷时间戳、进聊天日志。
        // 放在风控开关之前：就算关了敏感词风控，冷场唤醒等仍要有数据。
        // 实现内部吞掉所有异常，Redis 故障不会掐断消息链路
        chatActivityService.recordGroupMessage(groupId, userId, message);

        if (!enabled) {
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
            pointsService.deductPoints(userId, deductPoints, "违规扣分");
        } catch (Exception e) {
            log.warn("违规扣分失败：qq={}", userId, e);
        }

        long hitCount = increaseHitCount(groupId, userId);
        String at = "[CQ:at,qq=" + userId + "]";
        String announcement;

        if (hitCount <= 1) {
            String action = recalled ? "已撤回并扣除 " + deductPoints + " 积分" : "已记录并扣除 " + deductPoints + " 积分";
            announcement = at + " " + action + "，请注意群规喵～";
        } else {
            int seconds = hitCount == 2 ? muteSeconds : longMuteSeconds;
            boolean muted = seconds > 0 && ban(bot, groupId, userId, seconds);

            if (muted) {
                announcement = at + " 多次违规，已禁言 " + (seconds / 60) + " 分钟，请注意群规喵～";
            } else {
                announcement = at + " 多次违规，已记录。机器人没有禁言权限，请管理员处理喵～";
            }
        }

        // 公告限流：处置（撤回/扣分/禁言）已经执行，这里只压 @ 人的喊话，
        // 防止有人借"故意违规"驱动机器人刷屏公告骚扰全群
        if (tryAnnounce(groupId)) {
            sendGroupMsg(bot, groupId, announcement);
        } else {
            log.info("处罚公告触发频控，本次静默：group={}, qq={}, hitCount={}", groupId, userId, hitCount);
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
     * 24 小时内的违规次数——固定窗口：TTL 只在第一次计数时设置，
     * 之后每次违规不再续期，窗口从"本窗口首犯"起算、到点整窗清零。
     * <p>
     * 之前每次 increment 都 expire，窗口会无限顺延：每天违规 1 次的人
     * 计数永远不清零，第二次起被永久按"惯犯"处置，配置里的
     * "24 小时内 3 次"实际上永远测不准。
     * <p>
     * Redis 不可用时按 1 次处理：只撤回+扣分，不做禁言，宁可少罚也不误封。
     */
    private long increaseHitCount(long groupId, long userId) {
        String key = HIT_KEY_PREFIX + groupId + ":" + userId;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 只有窗口建立的那一次设 TTL；count==1 时 key 刚由 increment 创建，
                // 与 increment 之间不会插入其他写操作（同一 key 的 increment 串行），安全
                stringRedisTemplate.expire(key, offenseWindowHours, TimeUnit.HOURS);
            }
            return count == null ? 1 : count;
        } catch (Exception e) {
            log.warn("违规计数失败，按首次处理：group={}, qq={}", groupId, userId, e);
            return 1;
        }
    }

    /**
     * 群公告频控闸门：同一群的处罚公告最短间隔。
     * <p>
     * 为什么要限频：处罚公告是机器人自己发的，想闹的人连发几条打码违规内容，
     * 就能让机器人替他刷屏公告、骚扰全群——处罚系统被武器化的成本比人工刷屏还低。
     * 注意这里只限"公告"，撤回/扣分/禁言等实际处置每次照常执行，
     * 被限流的只是那条 @ 人的喊话（落日志可查，不丢失）。
     */
    private boolean tryAnnounce(long groupId) {
        String key = ANNOUNCE_KEY_PREFIX + groupId;
        try {
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", announceCooldownSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis 故障时选择"发公告"：宁可多发一条，不想让处置无声无息
            log.warn("处罚公告频控检查失败，本次照常公告：group={}", groupId, e);
            return true;
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