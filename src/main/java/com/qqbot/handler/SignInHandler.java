package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
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

    //私聊签到
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
            // 私聊事件里能拿到发送者昵称；拿不到就传 null，由 Service 用默认称呼兜底
            var sender = event.getPrivateSender();
            String nickname = sender == null ? null : sender.getNickname();
            String reply = signInService.signIn(qq, nickname);
            bot.sendPrivateMsg(qq, reply, false);
        }
        return MESSAGE_IGNORE;
    }

    //群聊签到
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();//群聊消息
        long groupId = event.getGroupId();//群号
        long qq = event.getUserId();//用户QQ号
        String botQq = "3809681102";

        //只要当消息包含了@机器人QQ号，才处理
        if (msg.contains("[CQ:at,qq=" + botQq + "]")) {
            String cleanMsg = msg.replace("[CQ:at,qq=" + botQq + "]", "").trim();//删除@机器人的代码

            //如果是签到
            if ("签到".equals(cleanMsg)) {
                // 冷却 5 秒，防止连点
                if (cooldownService.isCoolingDown("sign", qq, 5)) {
                    bot.sendGroupMsg(groupId, "操作太频繁啦，请 5 秒后再试！", false);//发送提示消息给群聊
                    return MESSAGE_IGNORE;
                }

                // 不在冷却中，正常走签到业务
                // 群名片优先，没设群名片就用 QQ 昵称
                var sender = event.getSender();
                String nickname = null;
                if (sender != null) {
                    nickname = (sender.getCard() != null && !sender.getCard().isBlank())
                            ? sender.getCard()
                            : sender.getNickname();
                }
                String reply = signInService.signIn(qq, nickname);
                //返回群聊结果
                bot.sendGroupMsg(groupId, reply, false);//发送签到结果给群聊
            }
        }
        return MESSAGE_IGNORE;
    }
}