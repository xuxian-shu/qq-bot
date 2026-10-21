package com.qqbot.service;

import java.io.IOException;

public interface WeatherService {

    /**
     * 用户没有指定城市时使用的默认城市
     */
    String DEFAULT_CITY = "南京";

    /**
     * 查询指定地点的今日天气，返回中文预报。
     *
     * @param city 城市名，中英文都可以（如“上海”“Tokyo”）；
     *             也可以直接给经纬度（如“39.90,116.40”）
     * @throws CityNotFoundException 城市名查不到对应坐标
     */
    String getForecast(String city) throws IOException;
}