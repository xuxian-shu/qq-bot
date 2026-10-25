package com.qqbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import com.qqbot.service.WeatherService;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Duration;

@Service
public class WeatherServiceImpl implements WeatherService {

    // OkHttpClient 内部有连接池和线程池，全局复用一个即可（与 MusicHandler 保持一致）
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .build();

    // ObjectMapper 是线程安全的，全局复用一个即可
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getNanJingForecast() throws IOException {
        //1.查询地址：南京未来三天天气
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=32.06"
                + "&longitude=118.79"
                + "&daily=weather_code,temperature_2m_max,"
                + "temperature_2m_min,precipitation_probability_max"
                + "&timezone=Asia%2FShanghai"
                + "&forecast_days=3";

        //2.构造GET请求
        Request request = new Request.Builder()
                .get()
                .url(url)
                .build();

        //发送请求，使用完后自动关闭请求
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("天气接口请求失败，HTTP 状态码："
                        + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("天气接口没有返回内容");
            }

            //将响应内容读取成字符串，只读取一次
            String json = body.string();
            //把json解析成树形结构，这样每一行的值都可以取出来
            JsonNode root = MAPPER.readTree(json);

            //获取daily对象，其中存放每天都预报
            JsonNode daily = root.get("daily");

// 每个数组的第 0 项，都对应今天
            String date = daily.get("time").get(0).asText();
            int weatherCode = daily.get("weather_code").get(0).asInt();
            String description = weatherDescription(weatherCode);
            double maxTemperature = daily.get("temperature_2m_max").get(0).asDouble();
            double minTemperature = daily.get("temperature_2m_min").get(0).asDouble();
            int rainProbability = daily.get("precipitation_probability_max").get(0).asInt();
            return String.join("\n",
                    "南京天气预报",
                    "日期：" + date,
                    "天气：" + description,
                    "气温：" + minTemperature + "～" + maxTemperature + "℃",
                    "最大降水概率：" + rainProbability + "%",
                    "数据来源：Open-Meteo"
            );


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
