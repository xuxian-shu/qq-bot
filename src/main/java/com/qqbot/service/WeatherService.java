package com.qqbot.service;

import java.io.IOException;

public interface WeatherService {
    /**
     * 查询南京今日天气，返回中文预报
     */
    String getNanJingForecast() throws IOException;
}
