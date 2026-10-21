package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.CityNotFoundException;
import com.qqbot.service.WeatherService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class WeatherHandler extends BotPlugin {

    @Resource
    private WeatherService weatherService;

    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String city = parseCity(event.getMessage());
        if (city == null) {
            // 不是天气指令，交给别的插件处理
            return MESSAGE_IGNORE;
        }

        String reply;
        try {
            reply = weatherService.getForecast(city);
        } catch (CityNotFoundException e) {
            reply = "没有找到城市「" + e.getCity() + "」，可以换个说法，"
                    + "或者直接发经纬度，例如：天气 39.90,116.40";
        } catch (IOException e) {
            reply = "查询天气失败，请稍后再试";
        }
        bot.sendPrivateMsg(event.getUserId(), reply, false);
        return MESSAGE_IGNORE;
    }

    /**
     * 支持“天气”、“天气 北京”、“北京天气”三种写法；
     * 只发“天气”时用默认城市；不是天气指令返回 null
     */
    private static String parseCity(String message) {
        if (message == null) {
            return null;
        }
        String text = message.strip();
        if (text.isEmpty()) {
            return null;
        }
        if ("天气".equals(text)) {
            return WeatherService.DEFAULT_CITY;
        }
        if (text.startsWith("天气")) {
            return trimToDefault(text.substring(2));
        }
        if (text.endsWith("天气")) {
            return trimToDefault(text.substring(0, text.length() - 2));
        }
        return null;
    }

    private static String trimToDefault(String city) {
        String trimmed = city.strip();
        return trimmed.isEmpty() ? WeatherService.DEFAULT_CITY : trimmed;
    }
}