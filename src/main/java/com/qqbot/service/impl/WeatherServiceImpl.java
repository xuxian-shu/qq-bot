package com.qqbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.service.WeatherService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class WeatherServiceImpl implements WeatherService {

    // OkHttpClient 内部有连接池和线程池，全局复用一个即可
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .build();

    // ObjectMapper 是线程安全的，全局复用一个即可
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 默认城市：南京的坐标。不传地点时直接用，省一次地理编码请求
    private static final String NANJING_LAT = "32.06";
    private static final String NANJING_LON = "118.79";

    @Override
    public String getWeather(String location) throws IOException {
        // 1. 地点为空 → 默认南京；带了地点就先做地理编码，换出经纬度
        String lat = NANJING_LAT;
        String lon = NANJING_LON;
        String cityName = "南京";
        if (location != null && !location.isBlank()) {
            String keyword = location.trim();
            JsonNode place = geocode(keyword);
            if (place == null) {
                return "找不到「" + keyword + "」这个地方喵，换个写法试试？";
            }
            lat = place.get("latitude").asText();
            lon = place.get("longitude").asText();
            // 用接口返回的规范地名展示，比如查"魔都"返回"上海市"
            cityName = place.path("name").asText(keyword);
        }

        // 2. 查当前天气 + 今天的最高最低温
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min"
                + "&timezone=Asia%2FShanghai&forecast_days=1";

        JsonNode root = getJson(url);
        if (root == null || root.get("current") == null) {
            throw new IOException("天气接口返回内容异常");
        }

        JsonNode current = root.get("current");
        double temperature = current.get("temperature_2m").asDouble();
        int weatherCode = current.get("weather_code").asInt();
        int humidity = current.get("relative_humidity_2m").asInt();
        double wind = current.get("wind_speed_10m").asDouble();
        double maxTemperature = root.path("daily").path("temperature_2m_max").path(0).asDouble(temperature);
        double minTemperature = root.path("daily").path("temperature_2m_min").path(0).asDouble(temperature);

        return String.join("\n",
                cityName + " 当前天气",
                "天气：" + weatherDescription(weatherCode),
                "气温：" + temperature + "℃（今天 " + minTemperature + "～" + maxTemperature + "℃）",
                "湿度：" + humidity + "%",
                "风速：" + wind + " km/h",
                "数据来源：Open-Meteo"
        );
    }

    /**
     * 地理编码：把中文地名换算成经纬度。
     * 用 Open-Meteo 自带的接口，免 key 免费；查不到返回 null
     */
    private JsonNode geocode(String location) throws IOException {
        String encoded = URLEncoder.encode(location, StandardCharsets.UTF_8);
        JsonNode root = getJson("https://geocoding-api.open-meteo.com/v1/search"
                + "?name=" + encoded + "&count=1&language=zh&format=json");
        if (root == null) {
            return null;
        }
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return null;
        }
        return results.get(0);
    }

    /**
     * 发 GET 请求并解析成 JsonNode，失败时抛 IOException
     */
    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("天气接口请求失败，HTTP 状态码：" + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("天气接口没有返回内容");
            }
            return MAPPER.readTree(body.string());
        }
    }

    private static String weatherDescription(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "晴间少云";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "降雪";
            case 77 -> "米雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95, 97 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知天气（代码：" + code + "）";
        };
    }
}
