package com.qqbot.utils;

import net.coobird.thumbnailator.Thumbnails;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ImageUtils {

    /**
     * 生成签到卡片图片，返回 PNG 的字节数组
     */
    public static byte[] generateCardBytes(long qq, String nickname, int points, String extraLine) throws Exception {
        // 1. 读取底图
        InputStream bgStream = ImageUtils.class.getClassLoader().getResourceAsStream("test_image.jpg");
        if (bgStream == null) {
            throw new RuntimeException("找不到 test_image.jpg，请检查是否放在了 src/main/resources 目录下！");
        }

        BufferedImage background;
        try (InputStream in = bgStream) {
            background = ImageIO.read(in);
        }
        if (background == null) {
            throw new RuntimeException("test_image.jpg 无法解析，请确认它是有效的图片文件！");
        }

        int width = background.getWidth();
        int height = background.getHeight();

        // 根据底图尺寸计算布局，换任何大小的底图都能自适应
        int margin = Math.max(6, Math.round(height * 0.06f));
        int avatarSize = Math.round(height * 0.45f);
        int avatarX = margin;
        int avatarY = (height - avatarSize) / 2;

        int panelX = avatarX + avatarSize + margin;
        int panelW = width - panelX - margin;
        int panelH = Math.round(height * 0.62f);
        int panelY = (height - panelH) / 2;

        // 2. 下载头像并裁剪成圆形
        BufferedImage avatar = downloadCircularAvatar(qq, avatarSize);

        // 3. 开始画图
        Graphics2D g2d = background.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); // 抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 4. 贴头像
        if (avatar != null) {
            g2d.drawImage(avatar, avatarX, avatarY, null);
        }

        // 5. 画一个半透明背景板，防止文字看不清
        g2d.setColor(new Color(0, 0, 0, 150));
        g2d.fillRoundRect(panelX, panelY, panelW, panelH, 20, 20);

        // 6. 写文字（三行：昵称 / 积分 / 连击信息，字号按面板宽度自适应；
        // 三行把面板高度分成 32%/57%/82% 三个基线，行距比两行版略挤，字号相应调小）
        int textX = panelX + margin;
        int maxTextWidth = width - textX - margin;
        int preferredFontSize = Math.max(12, Math.round(height * 0.11f));
        int textY1 = panelY + Math.round(panelH * 0.32f);
        int textY2 = panelY + Math.round(panelH * 0.57f);
        int textY3 = panelY + Math.round(panelH * 0.82f);

        g2d.setColor(Color.WHITE);
        drawFittedText(g2d, "昵称: " + nickname, textX, textY1, maxTextWidth, preferredFontSize);

        g2d.setColor(new Color(255, 215, 0)); // 金色
        drawFittedText(g2d, "积分: " + points, textX, textY2, maxTextWidth, preferredFontSize);

        g2d.setColor(Color.WHITE);
        drawFittedText(g2d, extraLine, textX, textY3, maxTextWidth, preferredFontSize);

        // 7. 释放画笔
        g2d.dispose();

        // 8. 转成 byte[] 并返回
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(background, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 自动缩小字号，字号到下限还放不下就截断并加省略号
     */
    private static void drawFittedText(Graphics2D g2d, String text, int x, int y, int maxWidth, int preferredSize) {
        int minSize = 10;
        int size = preferredSize;
        Font font = new Font("微软雅黑", Font.BOLD, size);
        while (size > minSize && g2d.getFontMetrics(font).stringWidth(text) > maxWidth) {
            size--;
            font = new Font("微软雅黑", Font.BOLD, size);
        }

        FontMetrics metrics = g2d.getFontMetrics(font);
        if (metrics.stringWidth(text) > maxWidth) {
            text = ellipsize(metrics, text, maxWidth);
        }

        g2d.setFont(font);
        g2d.drawString(text, x, y);
    }

    private static String ellipsize(FontMetrics metrics, String text, int maxWidth) {
        String ellipsis = "…";
        int length = text.length();
        while (length > 0 && metrics.stringWidth(text.substring(0, length) + ellipsis) > maxWidth) {
            length--;
        }
        return length == 0 ? ellipsis : text.substring(0, length) + ellipsis;
    }

    /**
     * 下载头像并变成圆形
     */
    private static BufferedImage downloadCircularAvatar(long qq, int size) throws Exception {
        String avatarUrl = "https://q1.qlogo.cn/g?b=qq&nk=" + qq + "&s=640";
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(avatarUrl).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            InputStream inputStream = response.body().byteStream();

            // 缩放头像
            BufferedImage resizedAvatar = Thumbnails.of(inputStream).size(size, size).asBufferedImage();

            // 裁剪成圆形
            BufferedImage circularAvatar = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = circularAvatar.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Ellipse2D.Double shape = new Ellipse2D.Double(0, 0, size, size);
            g2d.setClip(shape);
            g2d.drawImage(resizedAvatar, 0, 0, null);
            g2d.dispose();

            return circularAvatar;
        }
    }
}
