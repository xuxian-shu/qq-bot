package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.WeatherService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class WeatherHandler extends BotPlugin {
    @Resource
    private WeatherService weatherService;

    @Override
    public  int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        //只处理天气
        if(!"天气".equals(event.getMessage())) {
            return 0;
        }
        String reply;
        try{
            reply = weatherService.getNanJingForecast();

        }catch (IOException e){
            reply = "查询天气失败";
        }
        bot.sendPrivateMsg(event.getUserId(), reply,false);
        return 0;
    }
}
