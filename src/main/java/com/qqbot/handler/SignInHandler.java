package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.SignInService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class SignInHandler extends BotPlugin {

    @Resource
    private SignInService signInService;

    /**
     * 签到处理
     */
    //    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        // 只有当用户发的是“签到”时，才处理
        if ("签到".equals(event.getMessage())) {
            // 调用 Service 处理业务
            String reply = signInService.signIn(event.getUserId());
            // 把 Service 返回的结果发给用户
            bot.sendPrivateMsg(event.getUserId(), reply, false);
        }
        return MESSAGE_IGNORE;
    }
}