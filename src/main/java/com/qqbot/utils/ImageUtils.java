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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class ImageUtils {

    // 头像下载统一用这个客户端：设置超时，避免 QQ 头像接口卡住整张卡片的生成
    private static final OkHttpClient AVATAR_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

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
     * 生成今日运势卡片，返回 PNG 的字节数组。
     * 底图用代码画渐变，不依赖额外图片资源；头像下载失败时画占位圆，不影响整张卡。
     *
     * @param title     卡片标题（日期）
     * @param tier      运势等级，决定大字的颜色
     * @param scoreText 运势指数文案
     * @param lines     详情行，已经在调用方拼好
     */
    public static byte[] generateFortuneCardBytes(long qq, String nickname, String title,
                                                  String tier, String scoreText, List<String> lines) throws Exception {
        int width = 720;
        int height = 440;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 1. 深紫渐变底
        g2d.setPaint(new GradientPaint(0, 0, new Color(26, 16, 51), width, height, new Color(64, 33, 102)));
        g2d.fillRect(0, 0, width, height);

        // 2. 撒星星：用 qq + 标题做种子，同一天同一个人每次生成的星星位置都一样
        Random starRandom = new Random(qq * 31L + title.hashCode());
        for (int i = 0; i < 70; i++) {
            int x = starRandom.nextInt(width);
            int y = starRandom.nextInt(height);
            int radius = 1 + starRandom.nextInt(2);
            g2d.setColor(new Color(255, 255, 255, 30 + starRandom.nextInt(100)));
            g2d.fillOval(x, y, radius, radius);
        }

        // 3. 左侧头像（外面套一圈淡淡的光晕）
        int avatarSize = 150;
        int avatarX = 40;
        int avatarY = (height - avatarSize) / 2;
        g2d.setColor(new Color(255, 255, 255, 35));
        g2d.fillOval(avatarX - 8, avatarY - 8, avatarSize + 16, avatarSize + 16);

        BufferedImage avatar = null;
        try {
            avatar = downloadCircularAvatar(qq, avatarSize);
        } catch (Exception e) {
            // 头像拿不到不影响整张卡，下面画占位圆
        }
        if (avatar != null) {
            g2d.drawImage(avatar, avatarX, avatarY, null);
        } else {
            g2d.setColor(new Color(255, 255, 255, 45));
            g2d.fillOval(avatarX, avatarY, avatarSize, avatarSize);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("微软雅黑", Font.BOLD, 56));
            String initial = (nickname == null || nickname.isBlank())
                    ? "喵"
                    // 昵称第一个字可能占两个 char（emoji/生僻字），按码点切才不会切出半个字符
                    : nickname.substring(0, nickname.offsetByCodePoints(0, 1));
            FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString(initial,
                    avatarX + (avatarSize - fm.stringWidth(initial)) / 2,
                    avatarY + (avatarSize - fm.getHeight()) / 2 + fm.getAscent());
        }

        // 4. 右侧半透明信息面板
        int panelX = avatarX + avatarSize + 30;
        int panelY = 24;
        int panelW = width - panelX - 26;
        int panelH = height - 48;
        g2d.setColor(new Color(0, 0, 0, 90));
        g2d.fillRoundRect(panelX, panelY, panelW, panelH, 24, 24);

        int textX = panelX + 26;
        int maxTextWidth = panelX + panelW - 26 - textX;

        // 5. 标题（日期）+ 昵称
        g2d.setColor(new Color(185, 168, 217));
        drawFittedText(g2d, title == null ? "" : title, textX, panelY + 42, maxTextWidth, 18);

        String displayName = (nickname == null || nickname.isBlank()) ? "群友" : nickname;
        g2d.setColor(Color.WHITE);
        drawFittedText(g2d, displayName + " 的今日签文", textX, panelY + 86, maxTextWidth, 26);

        // 6. 运势等级大字 + 运势指数
        Color tierColor = tierColor(tier);
        int tierBaseline = panelY + 166;
        Font tierFont = new Font("微软雅黑", Font.BOLD, 56);
        g2d.setFont(tierFont);
        g2d.setColor(tierColor);
        g2d.drawString(tier == null ? "" : tier, textX, tierBaseline);
        int tierWidth = g2d.getFontMetrics(tierFont).stringWidth(tier == null ? "" : tier);

        g2d.setColor(new Color(230, 221, 245));
        g2d.setFont(new Font("微软雅黑", Font.PLAIN, 20));
        g2d.drawString(scoreText == null ? "" : scoreText, textX + tierWidth + 18, tierBaseline - 4);

        // 7. 详情行
        g2d.setColor(new Color(232, 224, 245));
        int lineY = panelY + 222;
        for (String line : lines) {
            drawFittedText(g2d, line, textX, lineY, maxTextWidth, 20);
            lineY += 34;
        }

        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 运势等级对应的颜色，大吉金色、凶色偏红
     */
    private static Color tierColor(String tier) {
        return switch (tier == null ? "" : tier) {
            case "大吉" -> new Color(255, 215, 0);
            case "中吉" -> new Color(255, 170, 80);
            case "小吉" -> new Color(130, 220, 150);
            case "平" -> new Color(160, 200, 255);
            case "小凶" -> new Color(255, 140, 140);
            case "凶" -> new Color(230, 90, 90);
            default -> Color.WHITE;
        };
    }

    /**
     * 帮助卡片的一节：小节标题 + 若干行正文
     */
    public record HelpSection(String title, List<String> lines) {
    }

    // 帮助卡片的配色：标题蓝灰、小节标题蓝色、小节左侧粉色竖条、正文深灰
    private static final Color HELP_HEADER_COLOR = new Color(61, 74, 108);
    private static final Color HELP_TITLE_COLOR = new Color(44, 88, 148);
    private static final Color HELP_BAR_COLOR = new Color(255, 122, 156);
    private static final Color HELP_BODY_COLOR = new Color(66, 66, 66);
    private static final Color HELP_FOOTER_COLOR = new Color(120, 120, 120);

    /**
     * 生成使用说明书图片，返回 PNG 的字节数组。
     * 底图 help_bg.jpg 等比放大裁切铺满画布，再压一层半透明白把颜色冲淡，
     * 这样深色文字落在浅底上才看得清。
     *
     * @param header   卡片顶部的欢迎语
     * @param sections 各小节（标题 + 正文行）
     * @param footer   卡片结尾的一句话
     */
    public static byte[] generateHelpCardBytes(String header, List<HelpSection> sections, String footer) throws Exception {
        InputStream bgStream = ImageUtils.class.getClassLoader().getResourceAsStream("help_bg.jpg");
        if (bgStream == null) {
            throw new RuntimeException("找不到 help_bg.jpg，请检查是否放在了 src/main/resources 目录下！");
        }

        BufferedImage background;
        try (InputStream in = bgStream) {
            background = ImageIO.read(in);
        }
        if (background == null) {
            throw new RuntimeException("help_bg.jpg 无法解析，请确认它是有效的图片文件！");
        }

        int width = 900;
        int pad = 40;
        int contentWidth = width - pad * 2;

        // 1. 文字内容长度不固定，先用一张 1x1 的假画布量一遍，算出整张图要多高
        Graphics2D measurer = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        int height;
        try {
            height = layoutHelpText(measurer, pad, false, header, sections, footer, contentWidth) + pad;
        } finally {
            measurer.dispose();
        }

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = canvas.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 2. 底图按“ cover”方式画上去：等比放大到两个方向都不小于画布，再居中裁掉多余部分
        double scale = Math.max((double) width / background.getWidth(), (double) height / background.getHeight());
        BufferedImage scaled = Thumbnails.of(background).scale(scale).asBufferedImage();
        int cutX = (scaled.getWidth() - width) / 2;
        int cutY = (scaled.getHeight() - height) / 2;
        g2d.drawImage(scaled, 0, 0, width, height, cutX, cutY, cutX + width, cutY + height, null);

        // 3. 压一层半透明白，把底图颜色冲淡（数字越大越"浅"，字越好认）
        g2d.setColor(new Color(255, 255, 255, 175));
        g2d.fillRect(0, 0, width, height);

        // 4. 画文字，排版逻辑和量高度时完全同一份代码，不会算错位
        layoutHelpText(g2d, pad, true, header, sections, footer, contentWidth);

        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 帮助卡片文字排版：draw=false 只往下推 y 坐标用来量总高度，draw=true 才真正落字。
     * 两种情况走的是同一套行距计算，所以量出来的高度和画出来的位置永远一致。
     *
     * @return 排版结束后的 y 坐标
     */
    private static int layoutHelpText(Graphics2D g2d, int startY, boolean draw,
                                      String header, List<HelpSection> sections, String footer, int contentWidth) {
        int pad = 26; // 内容区左右共同的内边距由调用方减掉，这里再往里收一点
        int y = startY;

        // 顶部欢迎语
        Font headerFont = new Font("微软雅黑", Font.BOLD, 30);
        FontMetrics fmHeader = g2d.getFontMetrics(headerFont);
        for (String line : wrapText(g2d, header == null ? "" : header, headerFont, contentWidth - pad)) {
            if (draw) {
                g2d.setFont(headerFont);
                g2d.setColor(HELP_HEADER_COLOR);
                g2d.drawString(line, 26 + pad, y + fmHeader.getAscent());
            }
            y += fmHeader.getHeight() + 4;
        }

        Font titleFont = new Font("微软雅黑", Font.BOLD, 21);
        Font bodyFont = new Font("微软雅黑", Font.PLAIN, 17);
        FontMetrics fmTitle = g2d.getFontMetrics(titleFont);
        FontMetrics fmBody = g2d.getFontMetrics(bodyFont);

        for (HelpSection section : sections) {
            y += 20; // 小节之间留一段空隙

            // 小节标题：左边一根粉色圆角竖条
            if (draw) {
                g2d.setColor(HELP_BAR_COLOR);
                g2d.fillRoundRect(26, y + 2, 6, fmTitle.getHeight() - 4, 6, 6);
                g2d.setFont(titleFont);
                g2d.setColor(HELP_TITLE_COLOR);
                g2d.drawString(section.title(), 26 + pad, y + fmTitle.getAscent());
            }
            y += fmTitle.getHeight() + 6;

            // 正文行，超宽的自动换行（中文没有空格，按字宽一点点累加）
            for (String line : section.lines()) {
                for (String wrapped : wrapText(g2d, line, bodyFont, contentWidth - pad)) {
                    if (draw) {
                        g2d.setFont(bodyFont);
                        g2d.setColor(HELP_BODY_COLOR);
                        g2d.drawString(wrapped, 26 + pad, y + fmBody.getAscent());
                    }
                    y += fmBody.getHeight() + 5;
                }
            }
        }

        // 结尾一句话
        y += 14;
        Font footerFont = new Font("微软雅黑", Font.PLAIN, 15);
        FontMetrics fmFooter = g2d.getFontMetrics(footerFont);
        for (String line : wrapText(g2d, footer == null ? "" : footer, footerFont, contentWidth - pad)) {
            if (draw) {
                g2d.setFont(footerFont);
                g2d.setColor(HELP_FOOTER_COLOR);
                g2d.drawString(line, 26 + pad, y + fmFooter.getAscent());
            }
            y += fmFooter.getHeight();
        }

        return y;
    }

    /**
     * 按像素宽度把一行文字折成多行，中文逐字累加宽度，不依赖空格
     */
    private static List<String> wrapText(Graphics2D g2d, String text, Font font, int maxWidth) {
        FontMetrics fm = g2d.getFontMetrics(font);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            // 当前行还没内容时先放一个字符，避免窄字符死循环
            if (current.length() > 0 && fm.stringWidth(current + ch) > maxWidth) {
                result.add(current.toString());
                current.setLength(0);
            }
            current.append(ch);
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
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
        Request request = new Request.Builder().url(avatarUrl).build();

        try (Response response = AVATAR_CLIENT.newCall(request).execute()) {
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
