package com.qqbot.service;

public interface CooldownService {

    /**
     * 检查是否在冷却中
     * @param key 业务前缀，比如 "sign"
     * @param qq  用户QQ号
     * @param seconds 冷却时长（秒）
     * @return true = 还在冷却（不能操作），false = 不在冷却（可以操作）
     */
    boolean isCoolingDown(String key, long qq, long seconds);
}
