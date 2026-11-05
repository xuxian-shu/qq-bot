package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.command.CommandWords;
import com.qqbot.service.CooldownService;
import com.qqbot.service.impl.LotteryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 积分抽奖插件：给膨胀的积分装上消耗出口。
 * 私聊发送「抽奖」，群聊「@我 抽奖」。
 * <p>
 * 指令词已登记在 {@link CommandWords}，AiHandler 的排除名单和破冰黑名单
 * 自动生效，不需要再同步任何清单。
 */
@Component
public class LotteryHandler extends BotPlugin {

    @Resource
    private LotteryService lotteryService;

    @Resource
    private CooldownService cooldownService;

    //私聊抽奖
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        if (!CommandWords.isLottery(msg)) {
            return MESSAGE_IGNORE;
        }
        long qq = event.getUserId();

        // 3 秒冷却：期望净亏的玩法连点也没意义，冷却只是防手滑
        if (cooldownService.isCoolingDown("lottery", qq, 3)) {
            bot.sendPrivateMsg(qq, "摇骰子的手歇一歇喵，3 秒后再抽～", false);
            return MESSAGE_IGNORE;
        }

        bot.sendPrivateMsg(qq, lotteryService.draw(qq), false);
        return MESSAGE_IGNORE;
    }

    //群聊抽奖：必须 @机器人，与其他群指令口径一致
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
        if (!CommandWords.isLottery(cleanMsg)) {
            return MESSAGE_IGNORE;
        }

        long groupId = event.getGroupId();
        long qq = event.getUserId();
        if (cooldownService.isCoolingDown("lottery", qq, 3)) {
            bot.sendGroupMsg(groupId, "摇骰子的手歇一歇喵，3 秒后再抽～", false);
            return MESSAGE_IGNORE;
        }

        bot.sendGroupMsg(groupId, lotteryService.draw(qq), false);
        return MESSAGE_IGNORE;
    }
}
