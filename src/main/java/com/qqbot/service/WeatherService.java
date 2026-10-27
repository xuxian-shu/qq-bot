package com.qqbot.service;

import java.io.IOException;

public interface WeatherService {
    /**
     * 查询指定地点的当前天气
     * @param location 地点名（如"上海"）；为空时默认查南京
     * @return 天气播报文本
     */
    String getWeather(String location) throws IOException;
}
