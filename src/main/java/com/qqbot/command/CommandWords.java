package com.qqbot.command;

/**
 * 全机器人指令词的唯一判定入口。
 * <p>
 * 背景：原先 WelcomeHandler（破冰黑名单）和 AiHandler（AI 排除名单）各自手抄
 * 一份指令清单，每加一个指令要同步 3 处（自己的 Handler + 这两份名单），
 * 漏一处的后果是：AI 对新指令抢答、或破冰奖励能用新指令白嫖。
 * <p>
 * 从现在起规矩只有一条：<b>新增指令 = 在本类加一个谓词并挂进 {@link #isAnyCommand}，
 * WelcomeHandler / AiHandler 自动生效，不用再改任何名单。</b>
 * <p>
 * 注意：这里判定的是"去掉 @机器人 前缀、去掉 CQ 码并 trim 之后"的正文，
 * 各调用方负责先把消息洗成这种裸文本。
 */
public final class CommandWords {

    private CommandWords() {
    }

    // ---------------------------------------------------------------- 各指令谓词

    /** 私聊发送：签到 */
    public static boolean isSignIn(String msg) {
        return eq(msg, "签到");
    }

    /** 打招呼（AI 排除名单原本就带 hello） */
    public static boolean isHello(String msg) {
        return msg != null && "hello".equalsIgnoreCase(msg.trim());
    }

    /** 积分 / 积分排行 */
    public static boolean isPoints(String msg) {
        return eq(msg, "积分") || eq(msg, "积分排行");
    }

    /** 点歌 歌名 */
    public static boolean isMusic(String msg) {
        return startsWith(msg, "点歌");
    }

    /** 提醒我 X分钟后 事情 */
    public static boolean isReminder(String msg) {
        return startsWith(msg, "提醒我");
    }

    /** 天气 / 天气 城市 */
    public static boolean isWeather(String msg) {
        return eq(msg, "天气") || startsWith(msg, "天气 ");
    }

    /** 答题 答案 / 今日一题 */
    public static boolean isQuiz(String msg) {
        return startsWith(msg, "答题") || eq(msg, "今日一题");
    }

    /** 今日运势 / 运势 / 求签 */
    public static boolean isFortune(String msg) {
        return eq(msg, "今日运势") || eq(msg, "运势") || eq(msg, "求签");
    }

    /** 帮助 / 指南 / 菜单 */
    public static boolean isHelp(String msg) {
        return eq(msg, "帮助") || eq(msg, "指南") || eq(msg, "菜单");
    }

    /** 绑定B站 / 解绑B站 / 我的B站 / 今晚看什么 */
    public static boolean isBili(String msg) {
        return eq(msg, "绑定B站") || eq(msg, "解绑B站")
                || eq(msg, "我的B站") || eq(msg, "今晚看什么");
    }

    /** 抽奖 / 积分抽奖 */
    public static boolean isLottery(String msg) {
        return eq(msg, "抽奖") || eq(msg, "积分抽奖");
    }

    // ---------------------------------------------------------------- 总入口

    /**
     * 是不是任何一条已知指令。
     * WelcomeHandler 用它拦"拿指令冒充自我介绍薅破冰奖励"，
     * AiHandler 用它保证"指令归 Handler 管，AI 不抢答"。
     */
    public static boolean isAnyCommand(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        return isSignIn(msg) || isHello(msg) || isPoints(msg) || isMusic(msg)
                || isReminder(msg) || isWeather(msg) || isQuiz(msg)
                || isFortune(msg) || isHelp(msg) || isBili(msg) || isLottery(msg);
    }

    // ---------------------------------------------------------------- 小工具

    /** trim 后全等（null 安全） */
    private static boolean eq(String msg, String command) {
        return msg != null && command.equals(msg.trim());
    }

    /** trim 后前缀匹配（null 安全） */
    private static boolean startsWith(String msg, String prefix) {
        return msg != null && msg.trim().startsWith(prefix);
    }
}
