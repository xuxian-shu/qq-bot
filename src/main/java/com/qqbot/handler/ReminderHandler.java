package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.CooldownService;
import com.qqbot.service.ReminderService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时提醒插件。
 * 私聊：提醒我 30分钟后 收衣服
 * 群聊：@我 提醒我 30分钟后 收衣服
 * <p>
 * 只负责解析指令和回复确认；挂起调度、到点发消息都在 ReminderService 里。
 * 注意：新指令要同步到 AiHandler 的排除名单，否则 AI 会对这些词再回一条。
 */
@Component
public class ReminderHandler extends BotPlugin {

    // 提醒我 + 数字 + 单位 + 内容，例：提醒我 30分钟后 收衣服
    private static final Pattern REMIND_PATTERN = Pattern.compile("提醒我\\s*(\\d+)\\s*(分钟|分|小时|时)后\\s*(.+)");

    private static final long MAX_MINUTES = 24 * 60;//24 小时封顶
    private static final int MAX_CONTENT_LENGTH = 100;

    @Resource
    private ReminderService reminderService;

    @Resource
    private CooldownService cooldownService;

    //私聊：提醒我 xx分钟后 xx
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        if (msg != null && msg.trim().startsWith("提醒我")) {
            handle(bot, event.getSelfId(), event.getUserId(), 0, msg.trim());
        }
        return MESSAGE_IGNORE;
    }

    //群聊：@我 提醒我 xx分钟后 xx
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        // 动态获取机器人自己的 QQ 号，与其他 Handler 保持一致
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";
        if (msg == null || !msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }
        String cleanMsg = msg.replace(atCode, "").trim();
        if (cleanMsg.startsWith("提醒我")) {
            handle(bot, event.getSelfId(), event.getUserId(), event.getGroupId(), cleanMsg);
        }
        return MESSAGE_IGNORE;
    }

    /**
     * 解析并注册提醒，统一处理私聊/群聊（groupId 为 0 即私聊）
     */
    private void handle(Bot bot, long selfId, long qq, long groupId, String cleanMsg) {
        Matcher m = REMIND_PATTERN.matcher(cleanMsg);
        if (!m.find()) {
            reply(bot, qq, groupId, "格式：提醒我 30分钟后 收衣服喵（支持 X分钟后 / X小时后，群里要 @我 再发）");
            return;
        }

        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2);
        String content = m.group(3).trim();

        // 先拦超大数字再做乘法，防止长时间换算溢出
        if (amount < 1 || amount > MAX_MINUTES) {
            reply(bot, qq, groupId, "提醒时间要在 1 分钟到 24 小时之间喵");
            return;
        }
        long minutes = (unit.startsWith("小时") || "时".equals(unit)) ? amount * 60 : amount;
        if (minutes < 1 || minutes > MAX_MINUTES) {
            reply(bot, qq, groupId, "提醒时间要在 1 分钟到 24 小时之间喵");
            return;
        }

        if (content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            reply(bot, qq, groupId, "提醒内容要在 1 到 100 个字之间喵");
            return;
        }

        // 5 秒冷却，防止连发
        if (cooldownService.isCoolingDown("remind", qq, 5)) {
            reply(bot, qq, groupId, "操作太频繁啦，请 5 秒后再试！");
            return;
        }

        if (!reminderService.addReminder(selfId, qq, groupId, content, Duration.ofMinutes(minutes))) {
            reply(bot, qq, groupId, "你挂着的提醒太多啦，最多同时 3 条喵");
            return;
        }
        reply(bot, qq, groupId, "收到喵！" + describe(minutes) + "后提醒你：" + content);
    }

    /**
     * 分钟数转成人话：90 → "1小时30分钟"
     */
    private String describe(long minutes) {
        if (minutes < 60) {
            return minutes + "分钟";
        }
        if (minutes % 60 == 0) {
            return (minutes / 60) + "小时";
        }
        return (minutes / 60) + "小时" + (minutes % 60) + "分钟";
    }

    /**
     * 按 groupId 路由回复：>0 群聊，否则私聊
     */
    private void reply(Bot bot, long qq, long groupId, String text) {
        if (groupId > 0) {
            bot.sendGroupMsg(groupId, text, false);
        } else {
            bot.sendPrivateMsg(qq, text, false);
        }
    }
}
