package com.qqbot;

import com.qqbot.service.WeatherService;
import com.qqbot.service.impl.WeatherServiceImpl;

import java.io.IOException;

public class WeatherApiDemo {

    public static void main(String[] args) throws IOException {
        // 创建天气服务
        WeatherService weatherService = new WeatherServiceImpl();

        // 调用服务，获取中文预报
        String forecast = weatherService.getNanJingForecast();

        // 打印返回结果
        System.out.println(forecast);
    }
}