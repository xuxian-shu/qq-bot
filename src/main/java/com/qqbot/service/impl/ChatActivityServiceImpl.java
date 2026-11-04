package com.qqbot.service.impl;

import com.qqbot.service.ChatActivityService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 群活跃度记录。Redis key 设计：
 * <pre>
 * activity:last:{群号}            最后一条人类发言时间戳，TTL 3 天（冷群自然过期）
 * activity:groups                 见过的群号集合（调度器靠它遍历，避免扫 key）
 * activity:log:{群号}             最近 N 条聊天日志 "qq|内容"，LIST 自动截断
 * activity:awake:{群号}           小时级唤醒节流闸门
 * activity:awake-streak:{群号}    同一段冷场的连续唤醒次数，有人说话即清零
 * </pre>
 * 机器人自身消息不经过 record（拦截器已在全局阻断），
 * 所以时间戳只统计人类发言，唤醒后也不会自己刷新自己。
 */
@Service
public class ChatActivityServiceImpl implements ChatActivityService {

    private static final Logger log = LoggerFactory.getLogger(ChatActivityServiceImpl.class);

    private static final String LAST_KEY = "activity:last:";
    private static final String GROUPS_KEY = "activity:groups";
    private static final String LOG_KEY = "activity:log:";
    private static final String AWAKE_KEY = "activity:awake:";
    private static final String STREAK_KEY = "activity:awake-streak:";

    // 日志里不落 CQ 码：@、图片 URL 对上下文没意义，还白占内存
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^]]*]");
    private static final int MAX_LINE_LENGTH = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${proactive.awake.cooldown-hours:1}")
    private long awakeCooldownHours;

    @Value("${proactive.awake.max-consecutive:3}")
    private long awakeMaxConsecutive;

    @Value("${proactive.log-size:100}")
    private int logSize;

    @Override
    public void recordGroupMessage(long groupId, long userId, String message) {
        try {
            stringRedisTemplate.opsForValue().set(LAST_KEY + groupId,
                    String.valueOf(System.currentTimeMillis()), 3, TimeUnit.DAYS);
            stringRedisTemplate.opsForSet().add(GROUPS_KEY, String.valueOf(groupId));
            // 有人说话了：连续唤醒计数归零，冷场唤醒重新计数。
            // 用 EXISTS 挡一道，绝大多数时候 streak 不存在，省掉一次 DELETE 往返
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(STREAK_KEY + groupId))) {
                stringRedisTemplate.delete(STREAK_KEY + groupId);
            }

            String text = CQ_CODE.matcher(message == null ? "" : message)
                    .replaceAll("").replace("\n", " ").trim();
            if (!text.isEmpty()) {
                if (text.length() > MAX_LINE_LENGTH) {
                    text = text.substring(0, MAX_LINE_LENGTH);
                }
                String key = LOG_KEY + groupId;
                stringRedisTemplate.opsForList().rightPush(key, userId + "|" + text);
                // 只留最近 logSize 条：LRANGE 负索引截头，O(1)，不会无限膨胀
                stringRedisTemplate.opsForList().trim(key, -logSize, -1);
                stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            // 每条群消息都会走这里，Redis 挂了只报一行原因，不刷堆栈
            log.warn("群活跃记录失败，本次跳过：group={}, {}", groupId, e.getMessage());
        }
    }

    @Override
    public Long getLastActiveMillis(long groupId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(LAST_KEY + groupId);
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("读取群活跃时间失败：group={}, {}", groupId, e.getMessage());
            return null;
        }
    }

    @Override
    public Set<Long> getKnownGroups() {
        try {
            Set<String> members = stringRedisTemplate.opsForSet().members(GROUPS_KEY);
            if (members == null || members.isEmpty()) {
                return Set.of();
            }
            Set<Long> result = new HashSet<>(members.size());
            for (String member : members) {
                try {
                    result.add(Long.parseLong(member));
                } catch (NumberFormatException ignored) {
                    // 脏数据直接跳过，不影响其他群
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("读取已知群列表失败，本轮跳过：{}", e.getMessage());
            return Set.of();
        }
    }

    @Override
    public List<String> getRecentMessages(long groupId, int limit) {
        try {
            if (limit <= 0) {
                return List.of();
            }
            List<String> list = stringRedisTemplate.opsForList()
                    .range(LOG_KEY + groupId, -limit, -1);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("读取群聊日志失败：group={}, {}", groupId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean tryAcquireAwakeSlot(long groupId) {
        try {
            // 闸门一：每群 cooldown-hours 内最多唤醒 1 次（setIfAbsent 即领即锁）
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                    AWAKE_KEY + groupId, "1", awakeCooldownHours, TimeUnit.HOURS);
            if (!Boolean.TRUE.equals(acquired)) {
                return false;
            }
            // 闸门二：同一段冷场最多 max-consecutive 次，群真死了就别追着说话
            Long streak = stringRedisTemplate.opsForValue().increment(STREAK_KEY + groupId);
            if (streak != null && streak == 1L) {
                stringRedisTemplate.expire(STREAK_KEY + groupId, 24, TimeUnit.HOURS);
            }
            return streak != null && streak <= awakeMaxConsecutive;
        } catch (Exception e) {
            // 失败关闭：Redis 抖动时宁可不说话，也不能失去频控去刷屏
            log.warn("唤醒额度获取失败，本次不唤醒：group={}, {}", groupId, e.getMessage());
            return false;
        }
    }
}
