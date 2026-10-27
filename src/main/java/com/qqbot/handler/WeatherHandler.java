package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.CooldownService;
import com.qqbot.service.WeatherService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 天气播报插件。
 * 私聊：天气 / 天气 上海
 * 群聊：@我 天气 / @我 天气 上海
 * 不带地点默认查南京。
 * <p>
 * 注意：新指令格式要同步到 AiHandler 的排除名单，否则 AI 会对这些词再回一条。
 */
@Component
public class WeatherHandler extends BotPlugin {

    @Resource
    private WeatherService weatherService;

    @Resource
    private CooldownService cooldownService;

    //私聊：天气 [地点]
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }
        String location = parseLocation(msg.trim());
        if (location == null) {
            return MESSAGE_IGNORE;
        }
        sendWeather(bot, event.getUserId(), 0, location);
        return MESSAGE_IGNORE;
    }

    //群聊：@我 天气 [地点]
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }
        // 动态获取机器人自己的 QQ 号，与其他 Handler 保持一致
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";
        if (!msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }
        String location = parseLocation(msg.replace(atCode, "").trim());
        if (location == null) {
            return MESSAGE_IGNORE;
        }
        sendWeather(bot, event.getUserId(), event.getGroupId(), location);
        return MESSAGE_IGNORE;
    }

    /**
     * 匹配"天气"指令，返回要查的地点：
     * null = 不是天气指令；空串 = 没带地点（查默认城市）；非空 = 要查的地点
     */
    private String parseLocation(String text) {
        // 中文输入习惯打全角空格，统一换成半角再解析
        text = text.replace('　', ' ');
        if ("天气".equals(text)) {
            return "";//默认城市
        }
        if (text.startsWith("天气 ")) {
            return text.substring("天气 ".length()).trim();
        }
        return null;
    }

    /**
     * 查天气并按 groupId 路由回复；5 秒冷却防止刷接口
     */
    private void sendWeather(Bot bot, long qq, long groupId, String location) {
        if (cooldownService.isCoolingDown("weather", qq, 5)) {
            reply(bot, qq, groupId, "操作太频繁啦，请 5 秒后再试！");
            return;
        }

        String reply;
        try {
            reply = weatherService.getWeather(location.isBlank() ? null : location);
        } catch (IOException e) {
            reply = "查询天气失败，稍后再试试喵";
        }
        reply(bot, qq, groupId, reply);
    }

    private void reply(Bot bot, long qq, long groupId, String text) {
        if (groupId > 0) {
            bot.sendGroupMsg(groupId, text, false);
        } else {
            bot.sendPrivateMsg(qq, text, false);
        }
    }
}
