package com.qqbot.service.impl;

import com.qqbot.service.BotImageService;
import com.qqbot.utils.ImageUtils;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
public class BotImageServiceImpl implements BotImageService {

    /**
     * 生成签到图片，并直接返回 QQ 可以识别的 CQ 码
     */
    @Override
    public String generateSignCardCqCode(long qq, String nickname, int points) {
        try {
            // 1. 调用底层工具类画图，拿到 byte[]
            byte[] imageBytes = ImageUtils.generateCardBytes(qq, nickname, points);

            // 2. 转 Base64
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // 3. 返回 CQ 码
            return "[CQ:image,file=base64://" + base64Image + "]";
        } catch (Exception e) {
            e.printStackTrace();
            return "图片生成失败啦，请联系管理员喵~";
        }
    }
}
