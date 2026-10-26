package com.qqbot.service;

public interface BotImageService {

    /**
     * 生成签到卡片，并返回 QQ 可以识别的 CQ 码
     * @param extraLine 卡片第三行的补充信息（连击天数、暴击等）
     */
    String generateSignCardCqCode(long qq, String nickname, int points, String extraLine);
}
