package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.command.CommandWords;
import com.qqbot.service.BiliService;
import com.qqbot.service.CooldownService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * B站助手指令入口。四条指令：
 * <pre>
 * 绑定B站    扫码登录，把登录态交给本喵（二维码优先私聊发送）
 * 解绑B站    删除登录态与日报快照
 * 我的B站    等级/硬币/大会员/UP主数据 个人卡片
 * 今晚看什么  根据 TA 的首页推荐随机挑 3 条视频
 * </pre>
 * 群里都要 @机器人；私聊直接发。
 * 指令词登记在 CommandWords，AiHandler 排除名单与破冰黑名单自动生效。
 */
@Component
public class BiliHandler extends BotPlugin {

    @Resource
    private BiliService biliService;

    @Resource
    private CooldownService cooldownService;

    /**
     * 判断一句话是不是 B站 指令。词表在 {@link CommandWords#isBili}，
     * 本方法只是给本 Handler 内部用的转发，保持公开 API 不变。
     * 加词请改 CommandWords（AiHandler / WelcomeHandler 那边自动生效）。
     */
    public static boolean isBiliCommand(String msg) {
        return CommandWords.isBili(msg);
    }

    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        if (!isBiliCommand(msg)) {
            return MESSAGE_IGNORE;
        }
        handle(bot, event.getUserId(), 0, msg.trim());
        return MESSAGE_IGNORE;
    }

    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";
        if (!msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }
        String cleanMsg = msg.replace(atCode, "").trim();
        if (!isBiliCommand(cleanMsg)) {
            return MESSAGE_IGNORE;
        }
        handle(bot, event.getUserId(), event.getGroupId(), cleanMsg);
        return MESSAGE_IGNORE;
    }

    private void handle(Bot bot, long qq, long groupId, String cmd) {
        switch (cmd) {
            case "绑定B站" -> {
                // 冷却 15 秒：绑定时会起二维码轮询，防止误触连点并发多个流程
                if (cooldownService.isCoolingDown("bili-bind", qq, 15)) {
                    reply(bot, qq, groupId, "操作太频繁啦，请 15 秒后再试喵～");
                    return;
                }
                biliService.startBinding(bot, qq, groupId);
            }
            case "解绑B站" -> reply(bot, qq, groupId,
                    biliService.unbind(qq) ? "已解绑喵，登录态已经删掉了～" : "你还没绑定B站喵");
            case "我的B站" -> {
                if (cooldownService.isCoolingDown("bili-query", qq, 5)) {
                    reply(bot, qq, groupId, "太频繁啦，5 秒后再试喵～");
                    return;
                }
                reply(bot, qq, groupId, biliService.buildProfile(qq));
            }
            case "今晚看什么" -> {
                if (cooldownService.isCoolingDown("bili-query", qq, 5)) {
                    reply(bot, qq, groupId, "太频繁啦，5 秒后再试喵～");
                    return;
                }
                reply(bot, qq, groupId, biliService.buildRecommendation(qq));
            }
            default -> {
                // isBiliCommand 已经过滤过，走不到这里
            }
        }
    }

    private void reply(Bot bot, long qq, long groupId, String text) {
        try {
            if (groupId > 0) {
                bot.sendGroupMsg(groupId, text, false);
            } else {
                bot.sendPrivateMsg(qq, text, false);
            }
        } catch (Exception ignored) {
            // 消息发送失败没有补救手段，交给框架日志
        }
    }
}