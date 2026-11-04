package com.qqbot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mikuac.shiro.core.Bot;
import com.qqbot.bili.BiliApiClient;
import com.qqbot.bili.BiliApiClient.NavInfo;
import com.qqbot.bili.BiliApiClient.QrPoll;
import com.qqbot.bili.BiliApiClient.QrSession;
import com.qqbot.bili.BiliApiClient.RecVideo;
import com.qqbot.bili.BiliApiClient.UpStats;
import com.qqbot.service.BiliService;
import com.qqbot.service.BindInfo;
import com.qqbot.service.BotImageService;
import com.qqbot.utils.ImageUtils.HelpSection;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B站助手实现。Redis 结构：
 * <pre>
 * bili:user:{qq}   绑定关系 JSON（含登录态 Cookie，安全敏感）
 * bili:users       全部已绑定 QQ 的集合（日报调度器遍历用）
 * bili:snap:{qq}   昨日数据快照 JSON（粉丝/总播放/获赞），日报算增量用
 * </pre>
 * 扫码轮询在 CompletableFuture 的公共池里跑，每个 QQ 同时只允许一个绑定流程；
 * 超时、风控、登录态失效全部降级成话术，不往消息线程抛异常。
 */
@Service
public class BiliServiceImpl implements BiliService {

    private static final Logger log = LoggerFactory.getLogger(BiliServiceImpl.class);

    private static final String USER_KEY = "bili:user:";
    private static final String USERS_KEY = "bili:users";
    private static final String SNAP_KEY = "bili:snap:";

    private static final String NOT_BOUND_TEXT =
            "你还没绑定B站喵～ 私聊发送「绑定B站」（群里 @我 绑定B站）扫码就能绑定啦";
    private static final String INTERFACE_ERROR_TEXT = "B站接口开小差了喵，过一会儿再试试～";

    // 进行中的绑定流程（同一 QQ 不允许并发开两个二维码，否则旧轮询会互相覆盖）
    private final Set<Long> bindingInProgress = ConcurrentHashMap.newKeySet();

    @Resource
    private BiliApiClient biliApiClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private BotImageService botImageService;

    @Value("${bili.qr-timeout-seconds:200}")
    private long qrTimeoutSeconds;

    @Value("${bili.poll-interval-ms:2000}")
    private long pollIntervalMs;

    // ---------------------------------------------------------------- 绑定

    @Override
    public void startBinding(Bot bot, long qq, long groupId) {
        if (!bindingInProgress.add(qq)) {
            reply(bot, qq, groupId, "上一个二维码还在等你扫喵，先去扫那个吧～");
            return;
        }
        try {
            QrSession session = biliApiClient.createQr();
            String qrCqCode = "[CQ:image,file=base64://"
                    + Base64.getEncoder().encodeToString(biliApiClient.qrPng(session.url())) + "]";
            String text = "请打开手机B站 App 扫码登录（约 3 分钟内有效），登录态由本喵代为保管：";
            boolean privateSent = sendPrivate(bot, qq, text + "\n" + qrCqCode);

            if (groupId > 0) {
                if (privateSent) {
                    bot.sendGroupMsg(groupId,
                            "[CQ:at,qq=" + qq + "] 登录二维码已私聊发给你啦，快去扫喵～", false);
                } else {
                    // 不是好友发不了私聊，退回群聊；提醒本人扫，避免别人截胡绑到你的记录上
                    bot.sendGroupMsg(groupId,
                            "[CQ:at,qq=" + qq + "] 私聊发不出去（加个好友吧喵），二维码放群里了，请自己扫码：\n"
                                    + qrCqCode, false);
                }
            }

            // 异步轮询，主线程立刻返回
            CompletableFuture.runAsync(() -> pollLoop(bot, qq, groupId, session.key()));
        } catch (Exception e) {
            bindingInProgress.remove(qq);
            log.warn("B站绑定二维码生成失败：qq={}", qq, e);
            reply(bot, qq, groupId, "二维码生成失败了（B站接口不太稳定），稍后再试一次喵~");
        }
    }

    private void pollLoop(Bot bot, long qq, long groupId, String qrcodeKey) {
        long deadline = System.currentTimeMillis() + qrTimeoutSeconds * 1000L;
        boolean scannedNotified = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollIntervalMs);
                QrPoll poll;
                try {
                    poll = biliApiClient.pollQr(qrcodeKey);
                } catch (Exception e) {
                    continue;//单次网络抖动，继续轮询
                }
                if (poll.status() == 0) {
                    finishBinding(bot, qq, groupId, poll.cookie());
                    return;
                }
                if (poll.status() == 86090 && !scannedNotified) {
                    scannedNotified = true;
                    reply(bot, qq, groupId, "已经扫到啦，请在手机上点「确认登录」喵～");
                } else if (poll.status() == 86038) {
                    reply(bot, qq, groupId, "二维码过期了喵，重新发「绑定B站」再来一次吧");
                    return;
                }
            }
            reply(bot, qq, groupId, "等太久二维码过期了喵，重新发「绑定B站」再来一次吧");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            bindingInProgress.remove(qq);
        }
    }

    private void finishBinding(Bot bot, long qq, long groupId, String pollCookie) {
        try {
            // 补一个 buvid3，降低推荐/nav 接口被风控的概率；失败不影响绑定
            String buvid3 = "";
            try {
                buvid3 = biliApiClient.fetchBuvid3();
            } catch (Exception ignored) {
                // 拿不到就算了
            }
            String cookie = buvid3.isBlank() ? pollCookie : pollCookie + "; buvid3=" + buvid3;

            NavInfo nav = biliApiClient.nav(cookie);
            if (nav == null) {
                reply(bot, qq, groupId, "扫码成功但登录态校验失败了喵，重新发「绑定B站」再试一次吧");
                return;
            }

            UpStats stats = biliApiClient.upStats(nav.mid());
            // 有投稿才算 UP 主；纯观众号不推日报
            boolean up = stats.videos() > 0;
            BindInfo info = new BindInfo(qq, groupId, nav.mid(), nav.uname(), up,
                    cookie, System.currentTimeMillis());
            save(info);

            reply(bot, qq, groupId, "绑定成功喵～ 欢迎 " + nav.uname() + "（UID " + nav.mid() + "）！"
                    + (up ? "\n本喵每天 20 点会把你的B站数据日报推给你～"
                    : "\n试试私聊发「今晚看什么」让本喵给你推视频喵"));
            String card = renderProfileCard(info, nav);
            if (card != null) {
                reply(bot, qq, groupId, card);
            }
            log.info("B站绑定成功：qq={}, mid={}, up={}", qq, nav.mid(), up);
        } catch (Exception e) {
            log.warn("B站绑定收尾失败：qq={}", qq, e);
            reply(bot, qq, groupId, "绑定过程中出了点问题喵，重新发「绑定B站」再试试吧");
        }
    }

    // ---------------------------------------------------------------- 解绑/查询

    @Override
    public boolean unbind(long qq) {
        try {
            Boolean existed = stringRedisTemplate.delete(USER_KEY + qq);
            stringRedisTemplate.opsForSet().remove(USERS_KEY, String.valueOf(qq));
            stringRedisTemplate.delete(SNAP_KEY + qq);
            return Boolean.TRUE.equals(existed);
        } catch (Exception e) {
            log.warn("B站解绑失败：qq={}, {}", qq, e.getMessage());
            return true;//Redis 抖了也按成功处理，避免用户反复操作
        }
    }

    @Override
    public boolean isBound(long qq) {
        return load(qq) != null;
    }

    @Override
    public String buildProfile(long qq) {
        BindInfo user = load(qq);
        if (user == null) {
            return NOT_BOUND_TEXT;
        }
        NavInfo nav;
        try {
            nav = biliApiClient.nav(user.cookie());
        } catch (Exception e) {
            log.warn("B站个人信息拉取失败：qq={}", qq, e);
            return INTERFACE_ERROR_TEXT;
        }
        if (nav == null) {
            unbind(qq);//登录态已失效，直接清掉，逼用户重新扫码
            return "B站登录态失效了喵（大概一个月会过期），重新发「绑定B站」扫个码吧～";
        }
        return renderProfileCard(user, nav);
    }

    @Override
    public String buildRecommendation(long qq) {
        BindInfo user = load(qq);
        if (user == null) {
            return NOT_BOUND_TEXT;
        }
        List<RecVideo> videos;
        try {
            videos = biliApiClient.recommend(user.cookie(), 12);
        } catch (Exception e) {
            log.warn("B站推荐拉取失败：qq={}", qq, e);
            return INTERFACE_ERROR_TEXT;
        }
        if (videos.isEmpty()) {
            return "B站这次没给本喵返回推荐（可能被风控了），过一会儿再试试喵～";
        }
        List<RecVideo> shuffled = new ArrayList<>(videos);
        Collections.shuffle(shuffled);
        StringBuilder sb = new StringBuilder("本喵掐指一算，今晚适合看这几个喵～\n");
        int count = Math.min(3, shuffled.size());
        for (int i = 0; i < count; i++) {
            RecVideo video = shuffled.get(i);
            sb.append(i + 1).append(". ").append(video.title()).append('\n')
                    .append("   UP: ").append(video.owner())
                    .append(" | 播放 ").append(formatCount(video.play()))
                    .append(" | ").append(video.duration()).append('\n')
                    .append("   https://www.bilibili.com/video/").append(video.bvid()).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 日报

    @Override
    public List<BindInfo> listBindings() {
        try {
            Set<String> qqs = stringRedisTemplate.opsForSet().members(USERS_KEY);
            if (qqs == null || qqs.isEmpty()) {
                return List.of();
            }
            List<BindInfo> result = new ArrayList<>(qqs.size());
            for (String qq : qqs) {
                BindInfo info = load(Long.parseLong(qq));
                if (info != null) {
                    result.add(info);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("读取B站绑定列表失败，本轮跳过：{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String buildDailyReport(BindInfo user) {
        if (user == null || !user.up()) {
            return null;
        }
        UpStats now = biliApiClient.upStats(user.mid());
        if (now.follower() == 0 && now.videos() == 0) {
            // 接口全空，基本是风控或账号异常，这一期不推
            return null;
        }
        Snapshot prev = loadSnapshot(user.qq());
        saveSnapshot(user.qq(), new Snapshot(now.follower(), now.videos()));

        List<HelpSection> sections = new ArrayList<>();
        sections.add(new HelpSection("粉丝", List.of(formatCount(now.follower())
                + delta(prev == null ? null : prev.follower(), now.follower()))));
        sections.add(new HelpSection("投稿数", List.of(formatCount(now.videos())
                + delta(prev == null ? null : prev.videos(), now.videos()))));

        String image = botImageService.generateHelpCardCqCode(
                "B站数据日报 · " + user.uname(), sections, "数据来自B站公开接口，次日同一时间自动更新喵～");
        return image != null ? image : textCard("B站数据日报 · " + user.uname(), sections);
    }

    // ---------------------------------------------------------------- 卡片渲染

    private String renderProfileCard(BindInfo user, NavInfo nav) {
        List<HelpSection> sections = new ArrayList<>();
        sections.add(new HelpSection("账号", List.of(nav.uname() + "（UID " + nav.mid() + "）")));
        sections.add(new HelpSection("等级", List.of("LV" + nav.level() + " · 当前经验 " + nav.exp())));
        sections.add(new HelpSection("硬币", List.of(nav.coins() + " 枚")));
        sections.add(new HelpSection("大会员", List.of(nav.vip()
                ? "是喵（到期 " + formatDate(nav.vipDueMillis()) + "）"
                : "不是喵，白嫖也挺好")));
        try {
            UpStats stats = biliApiClient.upStats(user.mid());
            if (user.up() || stats.videos() > 0) {
                sections.add(new HelpSection("UP主数据", List.of(
                        "粉丝 " + formatCount(stats.follower())
                                + " · 投稿 " + formatCount(stats.videos()) + " 个")));
            }
        } catch (Exception ignored) {
            // UP 数据拿不到就少一栏，不影响卡片
        }
        String image = botImageService.generateHelpCardCqCode(
                "我的B站 · 小喵代查", sections, "数据实时来自B站接口喵～");
        return image != null ? image : textCard("我的B站 · 小喵代查", sections);
    }

    private String textCard(String header, List<HelpSection> sections) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (HelpSection section : sections) {
            sb.append('【').append(section.title()).append("】");
            for (String line : section.lines()) {
                sb.append(line).append(' ');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String delta(Long before, long now) {
        if (before == null) {
            return "";
        }
        long d = now - before;
        if (d == 0) {
            return "（持平）";
        }
        return d > 0 ? "（+" + formatCount(d) + "）" : "（-" + formatCount(-d) + "）";
    }

    private static String formatCount(long n) {
        if (n >= 100_000_000L) {
            return String.format("%.1f亿", n / 100_000_000.0);
        }
        if (n >= 10_000L) {
            return String.format("%.1f万", n / 10_000.0);
        }
        return String.valueOf(n);
    }

    private static String formatDate(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")));
    }

    // ---------------------------------------------------------------- Redis 存取

    private void save(BindInfo info) {
        try {
            stringRedisTemplate.opsForValue().set(USER_KEY + info.qq(),
                    objectMapper.writeValueAsString(info));
            stringRedisTemplate.opsForSet().add(USERS_KEY, String.valueOf(info.qq()));
        } catch (Exception e) {
            log.warn("B站绑定关系保存失败：qq={}, {}", info.qq(), e.getMessage());
        }
    }

    private BindInfo load(long qq) {
        try {
            String json = stringRedisTemplate.opsForValue().get(USER_KEY + qq);
            return json == null ? null : objectMapper.readValue(json, BindInfo.class);
        } catch (Exception e) {
            log.warn("B站绑定关系读取失败：qq={}, {}", qq, e.getMessage());
            return null;
        }
    }

    /** 日报快照：昨天各指标的值，用于算增量 */
    private record Snapshot(long follower, long videos) {
    }

    private Snapshot loadSnapshot(long qq) {
        try {
            String json = stringRedisTemplate.opsForValue().get(SNAP_KEY + qq);
            return json == null ? null : objectMapper.readValue(json, Snapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveSnapshot(long qq, Snapshot snapshot) {
        try {
            // 快照保留 7 天，长时间不跑也不会无限堆积
            stringRedisTemplate.opsForValue().set(SNAP_KEY + qq,
                    objectMapper.writeValueAsString(snapshot), Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("B站日报快照保存失败：qq={}, {}", qq, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 消息发送

    /** 按绑定时的来源回复：群绑定回群，私聊绑定回私聊 */
    private void reply(Bot bot, long qq, long groupId, String msg) {
        try {
            if (groupId > 0) {
                bot.sendGroupMsg(groupId, msg, false);
            } else {
                bot.sendPrivateMsg(qq, msg, false);
            }
        } catch (Exception e) {
            log.warn("B站消息发送失败：qq={}", qq, e);
        }
    }

    /** 私聊发送；返回 false 表示可能没发出去（非好友等），调用方兜底 */
    private boolean sendPrivate(Bot bot, long qq, String msg) {
        try {
            var resp = bot.sendPrivateMsg(qq, msg, false);
            return resp == null || resp.getRetCode() == null || resp.getRetCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}