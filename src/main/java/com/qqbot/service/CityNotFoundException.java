package com.qqbot.service;

import java.io.IOException;

/**
 * 地理编码没有查到城市时抛出，方便上层给出友好提示
 */
public class CityNotFoundException extends IOException {

    private final String city;

    public CityNotFoundException(String city) {
        super("没有找到城市：" + city);
        this.city = city;
    }

    public String getCity() {
        return city;
    }
}