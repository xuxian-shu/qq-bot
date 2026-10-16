package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.AiService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class AiHandler extends BotPlugin {
    @Resource
    private AiService aiService;

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
}
