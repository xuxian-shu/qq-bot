package com.qqbot;

import com.qqbot.service.WeatherService;
import com.qqbot.service.impl.WeatherServiceImpl;

import java.io.IOException;

public class WeatherApiDemo {

    public static void main(String[] args) throws IOException {
        // 创建天气服务
        WeatherService weatherService = new WeatherServiceImpl();

        // 不带参数默认查南京，带城市名查对应城市
        String nanjing = weatherService.getWeather(null);
        System.out.println(nanjing);

        System.out.println("----------");

        String beijing = weatherService.getWeather("北京");
        System.out.println(beijing);
    }
}