package com.qqbot.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mikuac.shiro.common.utils.ArrayMsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.model.ArrayMsg;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class MusicHandler extends BotPlugin {

    // Jackson 的 ObjectMapper 是实例，readTree 不能通过类名静态调用；全局复用一个即可
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // OkHttpClient 要复用（内部有连接池），顺便设置超时，避免接口卡死整个 bot
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final String VKEYS_API = "https://api.vkeys.cn/v2/music/tencent";

    //处理群消息点歌
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        long groupId = event.getGroupId();
        long qq = event.getUserId();
        String botQq = "3809681102";

        //判断是否是@我,并且包含点歌
        if (msg.contains("[CQ:at,qq=" + botQq + "]")) {
            String cleanMsg = msg.replace("[CQ:at,qq=" + botQq + "]", "").trim();

            //判断是否是点歌
            if (cleanMsg.contains("点歌")) {
                String songName = cleanMsg.replace("点歌", "").trim();//去掉点歌

                if (songName.isEmpty()) {
                    bot.sendGroupMsg(groupId, "你想听什么歌喵？格式：@我 点歌 歌名", false);
                    return MESSAGE_IGNORE;
                }

                //调用音乐服务，播放音乐
                MusicResult result = searchMusic(songName);
                if (result == null) {
                    bot.sendGroupMsg(groupId, "没有找到这首歌喵！", false);//发送提示消息给群聊
                } else {
                    //发送音乐卡片，并检查 NapCat 是否真的发送成功
                    var resp = bot.sendGroupMsg(groupId, result.message(), false);
                    boolean failed = resp == null || !Integer.valueOf(0).equals(resp.getRetCode());
                    if (failed) {
                        System.err.println("点歌消息发送失败: status=" + (resp == null ? "null" : resp.getStatus())
                                + ", retcode=" + (resp == null ? "null" : resp.getRetCode()));
                        //卡片发不出去时至少把歌名和链接发出来
                        bot.sendGroupMsg(groupId, result.text(), false);
                    }
                }
            }
        }
        return MESSAGE_IGNORE;
    }

    /**
     * 搜索并构造音乐卡片消息。
     * 返回 null 表示没搜到；message 用于发送卡片，text 是卡片发送失败时的文字兜底。
     */
    private MusicResult searchMusic(String keyword) {
        try {
            // URL 编码，防止歌名里有中文或空格导致请求失败
            String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");

            // 1. 搜索：这个接口只返回歌曲信息，不返回播放直链
            JsonNode root = getJson(VKEYS_API + "?word=" + encodedKeyword);
            if (root == null) {
                return null;
            }

            JsonNode data = root.path("data");
            if (root.path("code").asInt() != 200 || !data.isArray() || data.size() == 0) {
                System.err.println("点歌接口无结果: " + keyword);
                return null;
            }

            JsonNode firstSong = data.get(0); // 拿第一首搜索结果
            String songId = firstSong.path("id").asText("");
            String songName = firstSong.path("song").asText("");
            String singer = firstSong.path("singer").asText("");
            String coverUrl = firstSong.path("cover").asText("");
            String mid = firstSong.path("mid").asText("");
            String playUrl = "";

            // 2. 详情：用 id 再查一次，才能拿到 url（音频直链）
            if (!songId.isEmpty()) {
                JsonNode detailRoot = getJson(VKEYS_API + "?id=" + URLEncoder.encode(songId, "UTF-8"));
                if (detailRoot != null && detailRoot.path("code").asInt() == 200) {
                    JsonNode detail = detailRoot.path("data");
                    playUrl = detail.path("url").asText("");
                    // 详情里的字段更全，有值就覆盖搜索结果
                    songName = detail.path("song").asText(songName);
                    singer = detail.path("singer").asText(singer);
                    coverUrl = detail.path("cover").asText(coverUrl);
                    mid = detail.path("mid").asText(mid);
                }
            }

            // QQ 音乐详情页要用 songmid 拼接，没有 mid 时退回数字 id
            String detailId = mid.isEmpty() ? songId : mid;
            String jumpUrl = "https://y.qq.com/n/ryqq/songDetail/" + detailId;

            // 3. 拿不到直链（版权/接口限制）就降级成文字，避免发一张点不开的废卡
            if (playUrl.isEmpty()) {
                String text = "找到《" + songName + "》- " + singer + "\n" + jumpUrl;
                return new MusicResult(ArrayMsgUtils.builder().text(text).build(), text);
            }

            // 部分客户端只认 https，直链支持 https，统一换掉
            if (playUrl.startsWith("http://")) {
                playUrl = "https://" + playUrl.substring("http://".length());
            }

            // 4. 构造 OneBot music 消息段。
            // 不要直接发 com.tencent.structmsg 的 json 卡片：新版 QQ 不认未签名的 ARK，
            // 会显示“发送者版本过低，无法展示内容”。
            // 交给 NapCat 的 music 段处理，它会调用 musicSignUrl 签名后再发送。
            Map<String, String> musicData = new HashMap<>();
            musicData.put("type", "custom");
            musicData.put("title", songName);
            musicData.put("content", singer);   // OneBot 规范里歌手字段叫 content，NapCat 会转成 singer
            musicData.put("url", jumpUrl);      // 点击卡片跳转的页面
            musicData.put("audio", playUrl);    // 播放直链
            musicData.put("image", coverUrl);   // 封面

            String fallbackText = "点歌：《" + songName + "》- " + singer + "\n" + jumpUrl;
            return new MusicResult(List.of(segment("music", musicData)), fallbackText);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 搜索结果：message 是待发送的消息段，text 是发送失败时的文字兜底。
     */
    private record MusicResult(List<ArrayMsg> message, String text) {
    }

    /**
     * 构造 {"type":"<type>","data":{...}} 消息段。
     * 用 TextNode 是为了保证每个值都序列化成字符串，
     * 同时避免 Shiro 的自动转义把音频直链里的 & 改成 &amp;。
     */
    private ArrayMsg segment(String type, Map<String, String> data) {
        ArrayMsg msg = new ArrayMsg();
        msg.setRawType(type);
        Map<String, TextNode> node = new HashMap<>();
        data.forEach((k, v) -> node.put(k, TextNode.valueOf(v)));
        msg.setData(node);
        return msg;
    }

    /**
     * 发 GET 请求并解析成 JsonNode，失败时打印原因并返回 null。
     */
    private JsonNode getJson(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            try (Response response = CLIENT.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();

                if (!response.isSuccessful()) {
                    //接口被限流/被拦截时，把状态码和响应体打出来，方便排查
                    System.err.println("点歌接口请求失败: HTTP " + response.code() + " url=" + url + " body=" + body);
                    return null;
                }
                return MAPPER.readTree(body);
            }
        } catch (Exception e) {
            System.err.println("点歌接口请求异常: url=" + url);
            e.printStackTrace();
            return null;
        }
    }
}
