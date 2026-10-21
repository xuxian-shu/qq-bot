package com.qqbot;

import com.qqbot.service.WeatherService;
import com.qqbot.service.impl.WeatherServiceImpl;

import java.io.IOException;

public class WeatherApiDemo {

    public static void main(String[] args) throws IOException {
        // 创建天气服务
        WeatherService weatherService = new WeatherServiceImpl();

        // 全球任意地点都可以查：中文名、英文名、甚至经纬度
        String[] cities = {"南京", "北京", "上海", "纽约", "伦敦", "悉尼",
                "New York", "London", "Sydney", "東京", "开罗",
                "39.90,116.40", "一个不存在的城市"};

        for (String city : cities) {
            System.out.println("===== " + city + " =====");
            try {
                System.out.println(weatherService.getForecast(city));
            } catch (IOException e) {
                System.out.println("查询失败：" + e.getMessage());
            }
            System.out.println();
        }
    }
}