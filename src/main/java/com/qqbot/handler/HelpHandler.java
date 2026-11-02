package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.BotImageService;
import com.qqbot.utils.ImageUtils.HelpSection;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 使用指南插件。
 * <p>
 * 和项目里其他 Handler 一样，必须加 @Component 交给 Spring 管理，
 * 再在 application.yml 的 shiro.plugin-list 里登记，Shiro 才会把它加载成机器人插件。
 */
@Component
public class HelpHandler extends BotPlugin {

    @Resource
    private BotImageService botImageService;

    /**
     * 说明书内容：结构化存一份，图片卡片和纯文字兜底都从这里取，改一处两边都生效。
     * 注意：正文里不要出现 [CQ:xxx] 这种格式，否则会被 Shiro 当成 CQ 码解析。
     */
    private static final String HELP_HEADER = "喵呜～欢迎翻开本喵的使用说明书 (*≧ω≦)";

    private static final List<HelpSection> HELP_SECTIONS = List.of(
            new HelpSection("基础聊天", List.of(
                    "私聊直接说话就能和本喵聊天啦～",
                    "群里要 @我 再说话哦！私聊 3 秒、群聊 5 秒冷却，别刷屏喵～")),
            new HelpSection("每日签到", List.of(
                    "私聊发送：签到",
                    "群聊发送：@我 签到",
                    "基础 10 分，连续签到每多 1 天多拿 2 分（封顶 +10），",
                    "还有 10% 概率暴击翻倍，本喵会画一张专属卡片给你喵！")),
            new HelpSection("积分", List.of(
                    "私聊发送：积分 / 积分排行",
                    "群聊发送：@我 积分 / @我 积分排行",
                    "排行榜收录的是在本群签过到的大佬们喵～")),
            new HelpSection("定时提醒", List.of(
                    "私聊发送：提醒我 30分钟后 收衣服",
                    "群聊发送：@我 提醒我 30分钟后 收衣服",
                    "支持 X分钟后 / X小时后，最长 24 小时，每人同时最多挂 3 条喵～")),
            new HelpSection("每日一题", List.of(
                    "每天 8 点自动在群里出题（单词/代码题）",
                    "群聊抢答：@我 答题 你的答案",
                    "看题：@我 今日一题",
                    "第一位答对的群友 +20 积分，手慢无喵！")),
            new HelpSection("点歌", List.of(
                    "群聊发送：@我 点歌 歌名",
                    "例如：@我 点歌 晴天",
                    "本喵会把 QQ 音乐卡片送上来～")),
            new HelpSection("天气预报", List.of(
                    "私聊发送：天气 或 天气 城市名",
                    "群聊发送：@我 天气 或 @我 天气 城市名",
                    "不写城市默认南京，例如：@我 天气 北京 喵～")),
            new HelpSection("今日运势 · 赛博求签", List.of(
                    "私聊发送：今日运势 / 运势 / 求签",
                    "群聊发送：@我 今日运势 / 运势 / 求签",
                    "每天一签，本喵会告诉你今天是 大吉/中吉/小吉/平/小凶/凶，",
                    "再附上运势指数、幸运数字、幸运色、幸运方位和今日宜忌，",
                    "最后送你一句箴言喵～",
                    "同一天算几次结果都一模一样，明天才会换，主打一个命定感（5 秒冷却）")),
            new HelpSection("再看一遍说明书", List.of(
                    "私聊发送：帮助 / 指南 / 菜单",
                    "群聊发送：@我 帮助 / 指南 / 菜单"))
    );

    private static final String HELP_FOOTER = "—— 本喵才不是话痨，只是怕你不会用而已喵！(￣^￣)";

    /**
     * 纯文字版说明书：万一图片生成失败（服务器缺字体、底图丢了等）就拿它兜底，
     * 保证用户一定有得看，和 FortuneHandler 的策略一致
     */
    private static final String HELP_REPLY = buildTextFallback();

    private static String buildTextFallback() {
        StringBuilder sb = new StringBuilder(HELP_HEADER).append("\n\n");
        for (HelpSection section : HELP_SECTIONS) {
            sb.append('【').append(section.title()).append('】').append('\n');
            for (String line : section.lines()) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
        }
        return sb.append(HELP_FOOTER).toString();
    }

    /**
     * 组装回复：优先生成说明书图片卡片，失败退回纯文字
     */
    private String helpReply() {
        String imageCqCode = botImageService.generateHelpCardCqCode(HELP_HEADER, HELP_SECTIONS, HELP_FOOTER);
        return imageCqCode != null ? imageCqCode : HELP_REPLY;
    }

    /**
     * 判断一句话是不是“帮助 / 指南 / 菜单”指令。
     * <p>
     * 做成 public static，是为了让 AiHandler 也能复用同一套判断，
     * 避免两个类各写一份关键词、以后加词时漏改一处。
     * <p>
     * 先 trim() 去掉首尾空格，这样用户发“ 帮助 ”也能识别。
     */
    public static boolean isHelpCommand(String msg) {
        if (msg == null) {
            return false;
        }
        String text = msg.trim();//去掉首尾空格
        return "帮助".equals(text)
                || "指南".equals(text)
                || "菜单".equals(text);
    }

    /**
     * 处理私聊消息：用户直接发“帮助/指南/菜单”即可。
     */
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        // 不是帮助指令，就不归本插件管，交给其他插件继续处理
        if (!isHelpCommand(event.getMessage())) {
            return MESSAGE_IGNORE;
        }

        // 发送说明书图片给发消息的用户（第二个参数 false 表示不再转义 CQ 码）
        bot.sendPrivateMsg(event.getUserId(), helpReply(), false);
        return MESSAGE_IGNORE;
    }

    /**
     * 处理群聊消息：必须 @机器人，再发送“帮助/指南/菜单”。
     */
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }

        // 动态获取机器人自己的 QQ 号（来自 Shiro 的 Event.getSelfId()），
        // 以后换机器人 / 换号也不用改代码
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";

        // 没 @ 机器人，说明不是在叫本喵，直接忽略
        if (!msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }

        // 把 @ 部分删掉，只留下用户真正说的话，例如 "@我 帮助" -> "帮助"
        String cleanMsg = msg.replace(atCode, "").trim();

        // 去掉 @ 后不是帮助指令，也不归本插件管
        if (!isHelpCommand(cleanMsg)) {
            return MESSAGE_IGNORE;
        }

        // 给群聊发送说明书图片
        bot.sendGroupMsg(event.getGroupId(), helpReply(), false);
        return MESSAGE_IGNORE;
    }
}
