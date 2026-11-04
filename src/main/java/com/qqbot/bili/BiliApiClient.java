package com.qqbot.bili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * B站接口的最小封装：只做 HTTP 和 JSON 解析，不碰 Redis / QQ 消息。
 * <p>
 * 接入范围（全部使用社区常青接口，避开需要 WBI 签名的路径）：
 * <ul>
 *   <li>扫码登录：passport 的 qrcode/generate + qrcode/poll（poll 成功时通过 Set-Cookie 拿到登录态）</li>
 *   <li>登录态校验 + 个人信息：/x/web-interface/nav</li>
 *   <li>首页推荐：/x/web-interface/index/top/rcmd（需要用户 cookie，返回"猜你喜欢"）</li>
 *   <li>UP 主公开数据：/x/relation/stat（粉丝）、/x/space/navnum（投稿数），无需登录
 *       （注：/x/space/upstat 的总播放/获赞接口已被B站下线，返回空 data，不再使用）</li>
 * </ul>
 * 注意：B站对非官方客户端有风控，接口随时可能返回 -352/-412。
 * 这里统一抛 IOException 或返回空结果，由上层降级成话术，绝不让异常冒到消息线程。
 */
@Component
public class BiliApiClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 连接复用：OkHttp 内部有连接池，全局一个实例即可（与 WeatherServiceImpl 同款）
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final String QR_GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header";
    private static final String QR_POLL_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?source=main-fe-header&qrcode_key=";
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String RCMD_URL =
            "https://api.bilibili.com/x/web-interface/index/top/rcmd?version=1&fresh_type=3&ps=";
    private static final String FINGER_URL = "https://api.bilibili.com/x/frontend/finger/spi";
    private static final String RELATION_URL = "https://api.bilibili.com/x/relation/stat?vmid=";
    private static final String NAVNUM_URL = "https://api.bilibili.com/x/space/navnum?mid=";

    /** 二维码登录会话：url 编码成二维码，key 用于轮询 */
    public record QrSession(String url, String key) {
    }

    /** 轮询结果：status 0=成功 86101=未扫码 86090=已扫码未确认 86038=已过期 */
    public record QrPoll(int status, String cookie) {
    }

    /** 登录用户信息，nav 接口一次全拿到 */
    public record NavInfo(long mid, String uname, int level, int exp, long coins,
                          boolean vip, int vipType, long vipDueMillis) {
    }

    /** 首页推荐视频 */
    public record RecVideo(String title, String owner, String bvid, long play, String duration) {
    }

    /** UP 主公开数据（粉丝数、投稿数）；拉取失败的字段为 0 */
    public record UpStats(long follower, long videos) {
    }

    public QrSession createQr() throws IOException {
        JsonNode root = getJson(QR_GENERATE_URL, null);
        if (root.path("code").asInt() != 0) {
            throw new IOException("二维码生成失败：" + root.path("message").asText());
        }
        JsonNode data = root.path("data");
        return new QrSession(data.path("url").asText(), data.path("qrcode_key").asText());
    }

    public QrPoll pollQr(String qrcodeKey) throws IOException {
        Request request = baseRequest(QR_POLL_URL + qrcodeKey, null).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            int status = root.path("data").path("code").asInt(-1);
            // 扫码成功时登录态在 Set-Cookie 里：SESSDATA / bili_jct / DedeUserID 等
            Map<String, String> jar = new LinkedHashMap<>();
            for (String header : response.headers("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            return new QrPoll(status, toCookieHeader(jar));
        }
    }

    /** 把二维码链接画成 PNG 字节，交给 Handler 转 base64 CQ 码发送 */
    public byte[] qrPng(String content) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** 匿名获取一个 buvid3，带上它能显著降低 nav/推荐接口的风控概率 */
    public String fetchBuvid3() throws IOException {
        JsonNode root = getJson(FINGER_URL, null);
        return root.path("data").path("b_3").asText("");
    }

    /**
     * 登录态校验 + 个人信息。
     * cookie 失效时 B站返回 code=-101，这里统一返回 null，由上层提示重新绑定。
     */
    public NavInfo nav(String cookie) throws IOException {
        JsonNode root = getJson(NAV_URL, cookie);
        JsonNode data = root.path("data");
        if (root.path("code").asInt() != 0 || !data.path("isLogin").asBoolean()) {
            return null;
        }
        JsonNode vip = data.path("vip");
        return new NavInfo(
                data.path("mid").asLong(),
                data.path("uname").asText(""),
                data.path("level_info").path("current_level").asInt(),
                data.path("level_info").path("current_exp").asInt(),
                data.path("money").path("gold").asLong(),
                vip.path("status").asInt() == 1,
                vip.path("vipType").asInt(),
                vip.path("dueDate").asLong());
    }

    /** 首页"猜你喜欢"；接口被风控或结构变化时返回空列表，不抛异常 */
    public List<RecVideo> recommend(String cookie, int count) throws IOException {
        JsonNode root = getJson(RCMD_URL + count, cookie);
        if (root.path("code").asInt() != 0) {
            return List.of();
        }
        List<RecVideo> videos = new ArrayList<>();
        for (JsonNode item : root.path("data").path("item")) {
            String bvid = item.path("bvid").asText("");
            if (bvid.isEmpty()) {
                continue;//广告位等非视频条目没有 bvid，直接跳过
            }
            videos.add(new RecVideo(
                    item.path("title").asText(""),
                    item.path("owner").path("name").asText(""),
                    bvid,
                    item.path("stat").path("view").asLong(),
                    item.path("duration").asText("")));
        }
        return videos;
    }

    /**
     * UP 主公开数据。两个接口分开容错：粉丝拉到、投稿数没拉到也不让整个日报失败。
     * （B站已下线总播放/获赞的匿名接口，这里只保留稳定可用的两项）
     */
    public UpStats upStats(long mid) {
        long follower = 0;
        long videos = 0;
        try {
            follower = getJson(RELATION_URL + mid, null).path("data").path("follower").asLong();
        } catch (IOException ignored) {
            // 该接口偶发 -412 风控，保留 0 继续
        }
        try {
            videos = getJson(NAVNUM_URL + mid, null).path("data").path("video").asLong();
        } catch (IOException ignored) {
            // 同上
        }
        return new UpStats(follower, videos);
    }

    private JsonNode getJson(String url, String cookie) throws IOException {
        Request request = baseRequest(url, cookie).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " @" + url);
            }
            return MAPPER.readTree(response.body().string());
        }
    }

    private Request.Builder baseRequest(String url, String cookie) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*");
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        return builder;
    }

    private static String toCookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}