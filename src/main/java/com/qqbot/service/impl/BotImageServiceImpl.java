package com.qqbot.service.impl;

import com.qqbot.service.BotImageService;
import com.qqbot.utils.ImageUtils;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

@Service
public class BotImageServiceImpl implements BotImageService {

    /**
     * 生成签到图片，并直接返回 QQ 可以识别的 CQ 码
     */
    @Override
    public String generateSignCardCqCode(long qq, String nickname, int points, String extraLine) {
        try {
            // 1. 调用底层工具类画图，拿到 byte[]
            byte[] imageBytes = ImageUtils.generateCardBytes(qq, nickname, points, extraLine);

            // 2. 转 Base64
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // 3. 返回 CQ 码
            return "[CQ:image,file=base64://" + base64Image + "]";
        } catch (Exception e) {
            e.printStackTrace();
            return "图片生成失败啦，请联系管理员喵~";
        }
    }

    /**
     * 生成今日运势卡片；失败返回 null，让 Handler 退回纯文字，
     * 不像签到那样返回错误文案（运势本身不依赖图片，图片只是锦上添花）
     */
    @Override
    public String generateFortuneCardCqCode(long qq, String nickname, String title,
                                            String tier, String scoreText, List<String> lines) {
        try {
            byte[] imageBytes = ImageUtils.generateFortuneCardBytes(qq, nickname, title, tier, scoreText, lines);
            return "[CQ:image,file=base64://" + Base64.getEncoder().encodeToString(imageBytes) + "]";
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
