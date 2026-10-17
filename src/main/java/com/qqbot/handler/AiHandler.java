package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.AiService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class AiHandler extends BotPlugin {
    @Resource
    private AiService aiService;

    //1.处理私聊消息
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        long qq = event.getUserId();

        //排除掉“签到”和“hello”，剩下的交给AI
        if (!"签到".equals(msg) && !"hello".equals(msg)) {
            String reply = aiService.chat(msg, String.valueOf(qq));//调用AI服务，传入用户消息和QQ号，返回AI回复
            bot.sendPrivateMsg(qq, reply, false);//发送AI回复给用户
        }
        return MESSAGE_IGNORE;
    }

    //2.处理群聊消息
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();//获取群聊消息内容
        long groupId = event.getGroupId();//获取群聊ID
        long qq = event.getUserId();//获取用户QQ号

        String botQq = "3809681102";//获取机器人QQ号

        //只要当消息包含了@机器人QQ号，才处理
        if (msg.contains("[CQ:at,qq=" + botQq + "]")) {
            //把@机器人的代码删掉，只留用户消息
            String cleanMsg = msg.replace("[CQ:at,qq=" + botQq + "]", "").trim();//删除@机器人的代码

            if (cleanMsg.isEmpty()) {//如果用户消息为空
                bot.sendGroupMsg(groupId, "神人，艾特我干什么", false);//发送提示消息给群聊
                return MESSAGE_IGNORE;
            }

            //调用ai回答
            String reply = aiService.chat(cleanMsg, String.valueOf(qq));//调用AI服务，传入用户消息和QQ号，返回AI回复
            bot.sendGroupMsg(groupId, reply, false);//发送AI回复给群聊
        }
        return MESSAGE_IGNORE;
    }

}
