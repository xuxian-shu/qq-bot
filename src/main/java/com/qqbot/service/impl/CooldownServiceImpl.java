package com.qqbot.service.impl;

import com.qqbot.service.CooldownService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CooldownServiceImpl implements CooldownService {

    private static final Logger log = LoggerFactory.getLogger(CooldownServiceImpl.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;//redis

    @Override
    public boolean isCoolingDown(String key, long qq, long seconds) {
        String redisKey = "cooldown:" + key + ":" + qq;
        try {
            // 尝试设置 key，如果不存在才设置成功（返回 true）
            Boolean success = stringRedisTemplate.opsForValue()//设置key
                    .setIfAbsent(redisKey, "1", seconds, TimeUnit.SECONDS);
            // 如果 success 是 true，说明刚设置成功（不在冷却中）
            // 如果 success 是 false 或 null，说明 key 已存在（在冷却中）
            return !Boolean.TRUE.equals(success);
        } catch (Exception e) {
            // Redis 不可用时不能让整个 Handler 崩掉，否则用户收不到任何回复。
            // 这里选择“放行”（返回 false）：优先保证机器人能用，代价是暂时失去防刷。
            // 只打一行原因，避免 Redis 长时间不可用时把日志刷爆。
            log.warn("Redis 冷却检查失败，本次放行：key={}，原因={}", redisKey, e.getMessage());
            return false;
        }
    }
}
