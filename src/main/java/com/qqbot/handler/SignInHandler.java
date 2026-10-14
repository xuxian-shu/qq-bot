package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.CooldownService;
import com.qqbot.service.SignInService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class SignInHandler extends BotPlugin {

    @Resource
    private SignInService signInService;

    @Resource
    private CooldownService cooldownService;

    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        if ("签到".equals(event.getMessage())) {
            long qq = event.getUserId();

            // 冷却 5 秒，防止连点
            if (cooldownService.isCoolingDown("sign", qq, 5)) {
                bot.sendPrivateMsg(qq, "操作太频繁啦，请 5 秒后再试！", false);
                return MESSAGE_IGNORE;
            }

            // 不在冷却中，正常走签到业务
            String reply = signInService.signIn(qq);
            bot.sendPrivateMsg(qq, reply, false);
        }
        return MESSAGE_IGNORE;
    }
}