package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.AiService;
import com.qqbot.service.CooldownService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import static com.qqbot.handler.HelpHandler.isHelpCommand;

@Component
public class AiHandler extends BotPlugin {
    @Resource
    private AiService aiService;
    @Resource
    private CooldownService cooldownService;//注入冷却服务

    //1.处理私聊消息
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        long qq = event.getUserId();

        // 指令类消息（签到、hello、帮助、天气、积分、提醒）不由 AI 处理，直接返回。
        // 各指令归各自的 Handler 管，不排除的话对应 Handler 回完后
        // AI 还会再回一条，用户收到双份回复。
        // 注意：这里必须 return，不能只把它们排除在冷却判断外面，
        // 否则后面照样会调用 aiService.chat()，AI 还是会插嘴。
        if ("签到".equals(msg) || "hello".equals(msg) || "天气".equals(msg)
                || "积分".equals(msg) || "积分排行".equals(msg)
                || msg.startsWith("提醒我") || isHelpCommand(msg)) {
            return MESSAGE_IGNORE;
        }

        //普通聊天才检查冷却：私聊，每人3秒
        if (cooldownService.isCoolingDown("ai", qq, 3)) {//如果在冷却中
            bot.sendPrivateMsg(qq, "本王还在冷却中，请3秒后再试", false);//发送提示消息给用户
            return MESSAGE_IGNORE;
        }

        String reply = aiService.chat(msg, String.valueOf(qq));//调用AI服务，传入用户消息和QQ号，返回AI回复
        bot.sendPrivateMsg(qq, reply, false);//发送AI回复给用户
        return MESSAGE_IGNORE;
    }

    //2.处理群聊消息
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();//获取群聊消息内容
        long groupId = event.getGroupId();//获取群聊ID
        long qq = event.getUserId();//获取用户QQ号

        // 动态获取机器人自己的 QQ 号，不再写死
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";

        //只要当消息包含了@机器人QQ号，才处理
        if (msg.contains(atCode)) {
            //把@机器人的代码删掉，只留用户消息
            String cleanMsg = msg.replace(atCode, "").trim();//删除@机器人的代码

            if (cleanMsg.isEmpty()) {//如果用户消息为空
                bot.sendGroupMsg(groupId, "神人，艾特我干嘛", false);//发送提示消息给群聊
                return MESSAGE_IGNORE;
            }

            // 指令类消息交给各自的 Handler：
            // 签到 → SignInHandler，点歌 → MusicHandler，帮助 → HelpHandler，
            // 积分 → PointsHandler，提醒 → ReminderHandler。
            // 这里必须 return，否则 AI 还会再回复一次。
            if ("签到".equals(cleanMsg) || "hello".equals(cleanMsg) || "积分".equals(cleanMsg)
                    || "积分排行".equals(cleanMsg) || cleanMsg.startsWith("点歌")
                    || cleanMsg.startsWith("提醒我") || isHelpCommand(cleanMsg)) {
                return MESSAGE_IGNORE;
            }

            //群聊，每人5秒冷却（防止刷屏）
            if (cooldownService.isCoolingDown("ai", qq, 5)) {//如果在冷却中
                bot.sendGroupMsg(groupId, "本王还在冷却中! 请5秒后再试", false);//发送提示消息给群聊
                return MESSAGE_IGNORE;
            }

            //发送AI回复,群聊ID+用户QQ号，作为chatId
            String reply = aiService.chat(cleanMsg, groupId + "_" + qq);//群聊ID+用户QQ号，作为chatId
            bot.sendGroupMsg(groupId, reply, false);//发送AI回复给群聊
        }
        return MESSAGE_IGNORE;
    }

}
