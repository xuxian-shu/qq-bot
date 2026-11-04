package com.qqbot.service;

import java.util.List;
import java.util.Set;

/**
 * 主动交互的数据地基：群最后发言时间戳、已知群登记、群聊日志缓冲。
 * <p>
 * 冷场唤醒调度器靠时间戳判断"群死了没"，话题接茬靠日志缓冲给 AI 喂上下文；
 * 以后要做 AI 群日报，也直接复用这份日志，不用再动消息链路。
 * <p>
 * 所有方法都不抛异常：Redis 故障只让主动交互降级，绝不能拖累消息主链路。
 */
public interface ChatActivityService {

    /**
     * 由全局拦截器在每条「人类」群消息上调用（机器人自己的消息不会进来）。
     * 刷新活跃时间戳、登记群号、追加聊天日志，并重置该群的连续唤醒计数。
     */
    void recordGroupMessage(long groupId, long userId, String message);

    /** 本群最后一次有人发言的时间戳（epoch 毫秒）；没记录过返回 null */
    Long getLastActiveMillis(long groupId);

    /** 服务运行以来见过的所有群号；Redis 不可用时返回空集合 */
    Set<Long> getKnownGroups();

    /** 最近 limit 条群聊日志，格式 "qq|内容"，从旧到新 */
    List<String> getRecentMessages(long groupId, int limit);

    /**
     * 尝试领取本群的"唤醒额度"：两层频控——
     * ①每群 cooldown-hours 内最多 1 次；②同一段冷场（自上次有人说话起）最多 max-consecutive 次。
     * 返回 true 表示本次可以发言，且额度已被消耗。
     */
    boolean tryAcquireAwakeSlot(long groupId);
}
