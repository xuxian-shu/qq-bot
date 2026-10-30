package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.BotImageService;
import com.qqbot.service.CooldownService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * 今日运势 / 赛博求签插件。
 * 私聊：今日运势 / 运势 / 求签
 * 群聊：@我 今日运势 / 运势 / 求签
 * <p>
 * 结果由「QQ 号 + 日期」做随机种子生成，所以同一天同一个人怎么算都一样，
 * 不用建表，第二天自然换签，也不会因为重启机器人而变。
 * <p>
 * 注意：新指令要同步到 AiHandler 的排除名单，否则 AI 会对这些词再回一条。
 */
@Component
public class FortuneHandler extends BotPlugin {

    /**
     * 运势等级：roll 落在 upperBound 以内即命中，概率合计 100%。
     * 分数区间和等级挂钩，避免出现「凶」却 95 分的矛盾结果。
     */
    private record Tier(String name, int upperBound, int minScore, int maxScore) {
    }

    private static final Tier[] TIERS = {
            new Tier("大吉", 4, 90, 100),   // 5%
            new Tier("中吉", 19, 75, 89),   // 15%
            new Tier("小吉", 49, 60, 74),   // 30%
            new Tier("平", 79, 50, 59),     // 30%
            new Tier("小凶", 94, 30, 49),   // 15%
            new Tier("凶", 99, 10, 29),     // 5%
    };

    record LuckyColor(String name, String emoji) {
    }

    // 幸运色带一个方块 emoji，文字版回复里一眼就能看出颜色
    private static final LuckyColor[] LUCKY_COLORS = {
            new LuckyColor("薄荷绿", "🟩"),
            new LuckyColor("天空蓝", "🟦"),
            new LuckyColor("樱花粉", "🌸"),
            new LuckyColor("柠檬黄", "🟨"),
            new LuckyColor("星空紫", "🟪"),
            new LuckyColor("珊瑚橙", "🟧"),
            new LuckyColor("奶油白", "⬜"),
            new LuckyColor("石墨黑", "⬛"),
            new LuckyColor("晚霞红", "🟥"),
            new LuckyColor("焦糖棕", "🟫"),
    };

    private static final String[] DIRECTIONS = {
            "正东", "东南", "正南", "西南", "正西", "西北", "正北", "东北"
    };

    // 宜 / 忌 的文案尽量轻松好玩，别写太正经的
    private static final String[] GOOD = {
            "写代码", "早睡", "运动", "喝奶茶", "摸鱼", "学习", "整理桌面", "给本喵投喂积分",
            "跟喜欢的人聊天", "debug 一次成功", "出门晒太阳", "换头像", "听歌", "复盘总结",
            "大胆表白", "删掉没用的文件", "认真吃早饭", "把想说的话说出口"
    };

    private static final String[] BAD = {
            "熬夜", "立 flag", "嘴硬", "空腹喝咖啡", "周一早起", "删库跑路", "跟产品对线",
            "通宵改需求", "冲动消费", "把 bug 留给明天", "在群里立誓", "刷手机到凌晨",
            "边吃饭边看代码", "挤地铁时回消息"
    };

    private static final String[] QUOTES = {
            "今天的 bug 会自己跑掉喵～",
            "勇敢的人先享受世界，勇敢的猫先抢到罐头。",
            "别急，代码总会编译的，只是时间问题。",
            "该吃吃该睡睡，天塌下来有本喵顶着。",
            "你比自己想象中更厉害，真的喵。",
            "遇到问题先喝口水，再去看日志。",
            "运气是留给有准备的人的，而本喵是留给你的。",
            "今天适合把想说的话说出来喵。",
            "慢一点也没关系，反正地球在转。",
            "记得对自己好一点，喵最心疼你了。",
            "所有的 bug 都是未来的经验值。",
            "万事胜意，前提是今天早点睡。",
            "别犹豫，先做五分钟再说。",
            "你不是一个人在战斗，还有本喵在。",
            "今天的风很温柔，适合摸鱼。",
            "复盘不是自责，是为了下次更帅。",
            "该来的总会来，该提交的 PR 也会被合并。",
            "生活嘛，七分运气三分努力，剩下九十分靠本喵。",
            "抬头看看天，今天的云很像一只猫。",
            "允许自己偶尔摆烂，是成年人的必修课。",
            "你今天已经做得很好了喵。",
            "把喜欢的事做久一点，就会变成擅长。",
            "别熬夜了，黑眼圈比 bug 还难修。",
            "好运正在派送中，请保持在线。"
    };

    @Resource
    private BotImageService botImageService;

    @Resource
    private CooldownService cooldownService;

    /**
     * 是不是运势指令。做成 public static 方便 AiHandler 复用，
     * 以后加词只改这一处
     */
    public static boolean isFortuneCommand(String msg) {
        if (msg == null) {
            return false;
        }
        String text = msg.trim();
        return "今日运势".equals(text)
                || "运势".equals(text)
                || "求签".equals(text);
    }

    //私聊：今日运势
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        if (!isFortuneCommand(event.getMessage())) {
            return MESSAGE_IGNORE;
        }

        long qq = event.getUserId();
        // 5 秒冷却：运势本身是按天固定的，频繁刷没有意义，还会白占线程
        if (cooldownService.isCoolingDown("fortune", qq, 5)) {
            bot.sendPrivateMsg(qq, "算得太频繁啦，请 5 秒后再试喵～", false);
            return MESSAGE_IGNORE;
        }

        var sender = event.getPrivateSender();
        String nickname = sender == null ? null : sender.getNickname();
        sendFortune(bot, qq, nickname, 0);
        return MESSAGE_IGNORE;
    }

    //群聊：@我 今日运势
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }

        // 动态获取机器人自己的 QQ 号，与其他 Handler 保持一致
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";
        if (!msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }
        if (!isFortuneCommand(msg.replace(atCode, "").trim())) {
            return MESSAGE_IGNORE;
        }

        long qq = event.getUserId();
        long groupId = event.getGroupId();
        if (cooldownService.isCoolingDown("fortune", qq, 5)) {
            bot.sendGroupMsg(groupId, "算得太频繁啦，请 5 秒后再试喵～", false);
            return MESSAGE_IGNORE;
        }

        // 群名片优先，没设群名片就用 QQ 昵称
        var sender = event.getSender();
        String nickname = null;
        if (sender != null) {
            nickname = (sender.getCard() != null && !sender.getCard().isBlank())
                    ? sender.getCard()
                    : sender.getNickname();
        }
        sendFortune(bot, qq, nickname, groupId);
        return MESSAGE_IGNORE;
    }

    /**
     * 生成运势并按 groupId 路由发送（>0 群聊，否则私聊）
     */
    private void sendFortune(Bot bot, long qq, String nickname, long groupId) {
        String name = (nickname == null || nickname.isBlank()) ? "群友" : nickname.trim();
        Fortune f = generate(qq, LocalDate.now());

        String message = botImageService.generateFortuneCardCqCode(
                qq, name, "今日运势 · " + dateText(f.date()), f.tier(),
                "运势指数 " + f.score(), cardLines(f));

        // 卡片生成失败（服务器缺字体、头像接口超时等）就退回纯文字，
        // 运势本身不依赖图片，保证用户一定有得看
        if (message == null) {
            message = textReply(f, name);
        }

        if (groupId > 0) {
            bot.sendGroupMsg(groupId, message, false);
        } else {
            bot.sendPrivateMsg(qq, message, false);
        }
    }

    private String textReply(Fortune f, String name) {
        return "🔮 " + name + " 的今日运势 · " + dateText(f.date()) + "\n\n"
                + "【" + f.tier() + "】运势指数 " + f.score() + "\n"
                + "幸运数字：" + f.luckyNumber() + "\n"
                + "幸运色：" + f.luckyColor().name() + " " + f.luckyColor().emoji() + "\n"
                + "幸运方位：" + f.direction() + "\n"
                + "宜：" + f.good() + "\n"
                + "忌：" + f.bad() + "\n"
                + "「" + f.quote() + "」";
    }

    /**
     * 卡片上的详情行。卡片里不放 emoji（AWT 对彩色 emoji 支持不稳定），
     * 和文字版分开拼
     */
    private List<String> cardLines(Fortune f) {
        return List.of(
                "幸运数字 " + f.luckyNumber() + "　幸运色 " + f.luckyColor().name(),
                "幸运方位 " + f.direction(),
                "宜：" + f.good(),
                "忌：" + f.bad(),
                "「" + f.quote() + "」"
        );
    }

    private String dateText(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /**
     * 核心：同一个 qq + 同一天，种子固定，抽到的结果就固定。
     * 用黄金分割常数混合一下，避免相邻 QQ 号（比如 1001 和 1002）的运势一模一样
     */
    static Fortune generate(long qq, LocalDate date) {
        long seed = qq * 0x9E3779B97F4A7C15L + date.toEpochDay();
        Random random = new Random(seed);

        Tier tier = pickTier(random.nextInt(100));
        int score = tier.minScore() + random.nextInt(tier.maxScore() - tier.minScore() + 1);
        int luckyNumber = 1 + random.nextInt(99);
        LuckyColor color = LUCKY_COLORS[random.nextInt(LUCKY_COLORS.length)];
        String direction = DIRECTIONS[random.nextInt(DIRECTIONS.length)];
        String good = pickTwo(GOOD, random);
        String bad = pickTwo(BAD, random);
        String quote = QUOTES[random.nextInt(QUOTES.length)];

        return new Fortune(date, tier.name(), score, luckyNumber, color, direction, good, bad, quote);
    }

    private static Tier pickTier(int roll) {
        for (Tier tier : TIERS) {
            if (roll <= tier.upperBound()) {
                return tier;
            }
        }
        return TIERS[TIERS.length - 1];
    }

    /**
     * 从文案池里挑两条不重复的，用「、」连起来
     */
    private static String pickTwo(String[] pool, Random random) {
        int first = random.nextInt(pool.length);
        // 在剩下的里挑，保证不和第一条重复
        int second = random.nextInt(pool.length - 1);
        if (second >= first) {
            second++;
        }
        return pool[first] + "、" + pool[second];
    }

    /**
     * 一次占卜的全部结果
     */
    record Fortune(LocalDate date, String tier, int score, int luckyNumber,
                   LuckyColor luckyColor, String direction, String good, String bad, String quote) {
    }
}
