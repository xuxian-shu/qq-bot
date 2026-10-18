package com.qqbot.service;

public interface BotImageService {

    /**
     * 生成签到卡片，并返回 QQ 可以识别的 CQ 码
     */
    String generateSignCardCqCode(long qq, String nickname, int points);
}
