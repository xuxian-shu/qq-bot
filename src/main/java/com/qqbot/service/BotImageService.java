package com.qqbot.service;

import java.util.List;

public interface BotImageService {

    /**
     * 生成签到卡片，并返回 QQ 可以识别的 CQ 码
     * @param extraLine 卡片第三行的补充信息（连击天数、暴击等）
     */
    String generateSignCardCqCode(long qq, String nickname, int points, String extraLine);

    /**
     * 生成今日运势卡片，返回 CQ 码；生成失败返回 null，由调用方退回纯文字
     *
     * @param title     卡片标题（日期）
     * @param tier      运势等级（大吉/中吉/…），卡片的等级大字用它决定颜色
     * @param scoreText 运势指数文案
     * @param lines     详情行（幸运数字、宜忌、箴言等）
     */
    String generateFortuneCardCqCode(long qq, String nickname, String title,
                                     String tier, String scoreText, List<String> lines);
}
