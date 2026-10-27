package com.qqbot.service.impl;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.qqbot.service.ReminderService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ReminderServiceImpl implements ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderServiceImpl.class);

    // 每人同时在挂的提醒上限，防刷屏
    private static final int MAX_PER_USER = 3;

    // 单线程调度器就够：提醒到点只是发一条消息的轻活。
    // 设成守护线程，不阻止 JVM 退出
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reminder-scheduler");
        t.setDaemon(true);
        return t;
    });

    // 每个用户当前挂着的提醒条数（qq -> 条数），到点或注册失败时减回去。
    // Handler 线程注册、调度线程触发减数，两边并发，用 AtomicInteger
    private final Map<Long, AtomicInteger> pendingCount = new ConcurrentHashMap<>();

    @Resource
    private BotContainer botContainer;// Shiro 的常驻 Bot 容器（@Component，可直接注入）

    @Override
    public boolean addReminder(long selfId, long qq, long groupId, String content, Duration delay) {
        AtomicInteger count = pendingCount.computeIfAbsent(qq, k -> new AtomicInteger());
        if (count.get() >= MAX_PER_USER) {
            return false;
        }
        count.incrementAndGet();

        try {
            scheduler.schedule(() -> fire(selfId, qq, groupId, content),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 注册失败（比如应用正在关闭）要把名额还回去
            count.decrementAndGet();
            log.warn("提醒注册失败：qq={}，内容={}，原因={}", qq, content, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * 到点执行：不在任何消息事件上下文里，必须用 BotContainer 找回 Bot 实例。
     * 注意：这里跑在调度线程上，只做发消息这一件事，别塞耗时操作
     */
    private void fire(long selfId, long qq, long groupId, String content) {
        pendingCount.computeIfAbsent(qq, k -> new AtomicInteger()).decrementAndGet();

        Bot bot = botContainer.robots.get(selfId);
        if (bot == null) {
            // 机器人重启过或掉线时拿不到实例，这条提醒只能放弃。
            // v1 提醒存在内存里，重启本来就会丢，v2 再做持久化
            log.warn("提醒触发时找不到 Bot 实例，selfId={}，内容={}", selfId, content);
            return;
        }

        String text = "到点啦喵！你之前让我提醒你：" + content;
        if (groupId > 0) {
            // 群聊必须带 at，不然群里看不出这条提醒是谁的
            bot.sendGroupMsg(groupId, "[CQ:at,qq=" + qq + "] " + text, false);
        } else {
            bot.sendPrivateMsg(qq, text, false);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
