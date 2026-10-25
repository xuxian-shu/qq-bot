package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import org.springframework.stereotype.Component;

/**
 * 使用指南插件。
 * <p>
 * 和项目里其他 Handler 一样，必须加 @Component 交给 Spring 管理，
 * 再在 application.yml 的 shiro.plugin-list 里登记，Shiro 才会把它加载成机器人插件。
 */
@Component
public class HelpHandler extends BotPlugin {

    /**
     * 使用说明书内容。
     * 注意：正文里不要出现 [CQ:xxx] 这种格式，否则会被 Shiro 当成 CQ 码解析。
     */
    private static final String HELP_REPLY = """
            喵呜～欢迎翻开本喵的使用说明书 (*≧ω≦)

            【基础聊天】
            私聊直接说话就能和本喵聊天啦～
            群里要 @我 再说话哦！私聊 3 秒、群聊 5 秒冷却，别刷屏喵～

            【每日签到】
            私聊发送：签到
            群聊发送：@我 签到
            每天都能领积分，本喵还会画一张专属卡片给你喵！

            【积分】
            私聊发送：积分 / 积分排行
            群聊发送：@我 积分 / @我 积分排行
            排行榜收录的是在本群签过到的大佬们喵～

            【点歌】
            群聊发送：@我 点歌 歌名
            例如：@我 点歌 晴天
            本喵会把 QQ 音乐卡片送上来～

            【天气预报】
            私聊发送：天气
            本喵就告诉你南京今天的天气喵（其他城市还在学习中……）

            【再看一遍说明书】
            私聊发送：帮助 / 指南 / 菜单
            群聊发送：@我 帮助 / 指南 / 菜单

            —— 本喵才不是话痨，只是怕你不会用而已喵！(￣^￣)
            """;

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

        // 发送说明书给发消息的用户（第二个参数 false 表示不再转义 CQ 码）
        bot.sendPrivateMsg(event.getUserId(), HELP_REPLY, false);
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

        // 给群聊发送说明书
        bot.sendGroupMsg(event.getGroupId(), HELP_REPLY, false);
        return MESSAGE_IGNORE;
    }
}
