package com.qqbot.scheduler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.qqbot.service.ChatActivityService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 冷场唤醒：每隔 check-ms 检查一次各群「最后一次人类发言」时间戳，
 * 沉默超过 silence-hours 就主动丢一条唤醒消息。不需要 AI，纯文案池。
 * <p>
 * 刷屏四道防线：
 * ①机器人自己的消息被拦截器在分发入口统一阻断——唤醒消息不算"有人说话"，
 *   不会刷新时间戳，也就不会出现"自己陪自己聊"；
 * ②每群每小时最多 1 次（Redis 节流闸门，见 ChatActivityService）；
 * ③同一段冷场最多连续唤醒 max-consecutive 次，群真死了就放弃；
 * ④quiet-start ~ quiet-end 静默时段不吵人。
 * <p>
 * 文案里出现关键词也没关系：自身消息进不了插件链，不会触发话题接茬。
 */
@Component
public class ColdGroupAwakeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ColdGroupAwakeScheduler.class);

    private static final List<String> WAKE_TEXTS = List.of(
            "群里安静得我都要生锈了喵~ 大家都去哪摸鱼啦？",
            "这么久没人说话，不会是背着我开红包吧喵？",
            "冷清成这样，来句「今日运势」给本喵测一卦如何喵~",
            "报告！本群已冷场超过 2 小时，再不上线就要长蘑菇了喵🍄",
            "闲得慌的可以「签到」领积分，攒着抽奖不香吗喵~"
    );

    @Resource
    private ChatActivityService chatActivityService;

    @Resource
    private BotContainer botContainer;

    @Value("${proactive.awake.enabled:true}")
    private boolean enabled;

    @Value("${proactive.awake.silence-hours:2}")
    private long silenceHours;

    @Value("${proactive.awake.quiet-start-hour:23}")
    private int quietStartHour;

    @Value("${proactive.awake.quiet-end-hour:8}")
    private int quietEndHour;

    @Scheduled(fixedDelayString = "${proactive.awake.check-ms:600000}")
    public void wakeUpColdGroups() {
        if (!enabled || inQuietHours(LocalTime.now().getHour())) {
            return;
        }
        // 定时线程不在消息事件里，和 DailyQuizScheduler 同款方式取常驻 Bot
        Bot bot = botContainer.robots.isEmpty() ? null : botContainer.robots.values().iterator().next();
        if (bot == null) {
            return;//机器人不在线，没东西可发
        }

        long now = System.currentTimeMillis();
        long silenceMs = silenceHours * 3_600_000L;
        for (Long groupId : chatActivityService.getKnownGroups()) {
            try {
                Long last = chatActivityService.getLastActiveMillis(groupId);
                // 没记录过的群（比如刚重启）不动，避免误判成冷场
                if (last == null || now - last < silenceMs) {
                    continue;
                }
                // 频控都在服务里：小时级闸门 + 连续唤醒上限
                if (!chatActivityService.tryAcquireAwakeSlot(groupId)) {
                    continue;
                }
                String text = WAKE_TEXTS.get(ThreadLocalRandom.current().nextInt(WAKE_TEXTS.size()));
                bot.sendGroupMsg(groupId, text, false);
                log.info("冷场唤醒已发送：group={}, 静默 {} 分钟", groupId, (now - last) / 60000);
            } catch (Exception e) {
                // 单个群失败不影响其他群，下一轮还会再试
                log.warn("冷场唤醒失败，下一轮重试：group={}", groupId, e);
            }
        }
    }

    /** 静默时段，支持跨午夜配置：23~8 表示 hour >= 23 或 hour < 8 */
    private boolean inQuietHours(int hour) {
        if (quietStartHour <= quietEndHour) {
            return hour >= quietStartHour && hour < quietEndHour;
        }
        return hour >= quietStartHour || hour < quietEndHour;
    }
}
