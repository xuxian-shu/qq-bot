package com.qqbot.service.impl;

import com.qqbot.service.CooldownService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CooldownServiceImpl implements CooldownService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;//redis

    @Override
    public boolean isCoolingDown(String key, long qq, long seconds) {
        String redisKey = "cooldown:" + key + ":" + qq;
        // 尝试设置 key，如果不存在才设置成功（返回 true）
        Boolean success = stringRedisTemplate.opsForValue()//设置key
                .setIfAbsent(redisKey, "1", seconds, TimeUnit.SECONDS);
        // 如果 success 是 true，说明刚设置成功（不在冷却中）
        // 如果 success 是 false 或 null，说明 key 已存在（在冷却中）
        return !Boolean.TRUE.equals(success);
    }
}
