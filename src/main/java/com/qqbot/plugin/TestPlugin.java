package com.qqbot.plugin;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import org.springframework.stereotype.Component;

@Component
public class TestPlugin extends BotPlugin {

    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        // 1. 获取用户发来的消息
        String msg = event.getMessage();
        // 2. 获取发消息的QQ号
        long userId = event.getUserId();

        // 3. 简单逻辑：如果用户发了 "hello"，我们就回复他
        if ("hello".equals(msg)) {
            String replyMsg = "你好！我是你的qq机器人，已成功连接！";
            // 发送私聊消息
            bot.sendPrivateMsg(userId, replyMsg, false);
        }

        // 返回 MESSAGE_IGNORE 表示继续传递给后续插件
        return MESSAGE_IGNORE;
    }
}