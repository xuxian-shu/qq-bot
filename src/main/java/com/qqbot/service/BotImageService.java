package com.qqbot.service;

import com.qqbot.utils.ImageUtils;

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

    /**
     * 生成使用说明书卡片，返回 CQ 码；生成失败返回 null，由调用方退回纯文字
     *
     * @param header   卡片顶部的欢迎语
     * @param sections 各小节（标题 + 正文行）
     * @param footer   卡片结尾的一句话
     */
    String generateHelpCardCqCode(String header, List<ImageUtils.HelpSection> sections, String footer);
}
