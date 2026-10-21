package com.qqbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqbot.service.CityNotFoundException;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class WeatherServiceImpl implements WeatherService {

    /**
     * 地理编码接口：把“城市名”翻译成“经纬度 + 时区”
     */
    private static final String GEOCODING_API = "https://geocoding-api.open-meteo.com/v1/search";

    /**
     * 天气预报接口
     */
    private static final String FORECAST_API = "https://api.open-meteo.com/v1/forecast";

    /**
     * 中文索引和英文索引是分开的，所以两种都要查：
     * 只查 zh 搜不到“New York”，只查 en 搜不到“北京”
     */
    private static final String LANGUAGE_ZH = "zh";
    private static final String LANGUAGE_EN = "en";
    private static final String[] SEARCH_LANGUAGES = {LANGUAGE_ZH, LANGUAGE_EN};

    private static final int SEARCH_COUNT = 10;

    /**
     * 名字完全对上时的加分，要远大于人口数，保证精确匹配优先
     */
    private static final long EXACT_NAME_BONUS = 1_000_000_000_000L;

    // 客户端可以复用，不用每次查询都 new 一个新的
    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getForecast(String city) throws IOException {
        // 1. 城市名 -> 经纬度
        Location location = resolveLocation(city);

        // 2. 用这个经纬度去查天气
        String json = get(FORECAST_API
                + "?latitude=" + location.latitude()
                + "&longitude=" + location.longitude()
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "precipitation_probability_max"
                // 时区跟着地点走，这样“今天”是当地时间，而不是北京时间
                + "&timezone=" + encode(location.timezone())
                + "&forecast_days=3");

        JsonNode daily = objectMapper.readTree(json).get("daily");
        if (daily == null || daily.get("time") == null || daily.get("time").isEmpty()) {
            throw new IOException("天气接口没有返回预报数据");
        }

        // 每个数组的第 0 项都对应“今天”
        String date = daily.get("time").get(0).asText();
        int weatherCode = daily.get("weather_code").get(0).asInt();
        String description = weatherDescription(weatherCode);
        double maxTemperature = daily.get("temperature_2m_max").get(0).asDouble();
        double minTemperature = daily.get("temperature_2m_min").get(0).asDouble();
        int rainProbability = daily.get("precipitation_probability_max").get(0).asInt();

        return String.join("\n",
                location.name() + "天气预报",
                "地点：" + location.fullName(),
                "日期：" + date,
                "天气：" + description,
                "气温：" + minTemperature + "～" + maxTemperature + "℃",
                "最大降水概率：" + rainProbability + "%",
                "数据来源：Open-Meteo"
        );
    }

    /**
     * 把用户输入解析成坐标：优先当作“经纬度”，否则调用地理编码接口查询
     */
    private Location resolveLocation(String city) throws IOException {
        String keyword = (city == null || city.isBlank()) ? DEFAULT_CITY : city.strip();

        // 支持直接输入经纬度，例如 “39.90,116.40”，方便查小地方
        Location coordinates = parseCoordinates(keyword);
        if (coordinates != null) {
            return coordinates;
        }

        // 常用国外城市先查别名表：GeoNames 的中文索引简繁混存，
        // 搜“纽约”会一条都搜不到，换成“New York”才稳
        String alias = CITY_ALIASES.get(keyword);
        String searchKeyword = alias == null ? keyword : alias;

        // 中英文索引各查一遍，按 id 合并同一个地点
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        for (String language : SEARCH_LANGUAGES) {
            for (JsonNode node : search(searchKeyword, language)) {
                long id = node.path("id").asLong();
                candidates.merge(id, new Candidate(node, language), Candidate::merge);
            }
        }

        if (candidates.isEmpty()) {
            throw new CityNotFoundException(keyword);
        }

        // 优先“名字完全对得上”的结果，其次选人口最多的：
        // 这样“New York”会命中纽约市，而不是同名的英国小村庄
        Candidate best = null;
        long bestScore = Long.MIN_VALUE;
        for (Candidate candidate : candidates.values()) {
            long score = candidate.score(searchKeyword);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        JsonNode base = best.base();          // 坐标、时区、人口以拉丁索引为准
        JsonNode chinese = best.chinese();    // 中文名优先用中文索引里的

        String name;
        String admin1;
        String country;
        if (chinese == null) {
            // 没有中文资料时，别名城市只显示中文名，避免“United States New York”这种半英半中
            name = alias != null ? keyword : base.path("name").asText(searchKeyword);
            admin1 = "";
            country = "";
        } else {
            name = alias != null ? keyword : chinese.path("name").asText(searchKeyword);
            admin1 = chinese.path("admin1").asText("");
            country = chinese.path("country").asText("");
        }

        return new Location(name, admin1, country,
                base.get("latitude").asDouble(),
                base.get("longitude").asDouble(),
                // timezone=auto 表示由 Open-Meteo 根据坐标自动判断时区
                base.path("timezone").asText("auto"));
    }

    /**
     * 查一种语言的地理编码索引，没有结果时返回空数组
     */
    private JsonNode search(String keyword, String language) throws IOException {
        String json = get(GEOCODING_API
                + "?name=" + encode(keyword)
                + "&count=" + SEARCH_COUNT
                + "&language=" + language
                + "&format=json");

        JsonNode results = objectMapper.readTree(json).get("results");
        return results == null ? objectMapper.createArrayNode() : results;
    }

    /**
     * 去掉空格、连字符并转小写，让“new york”和“New-York”算同一个名字
     */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s'’-]", "");
    }

    private static Location parseCoordinates(String text) {
        String[] parts = text.split("\\s*,\\s*|\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            double latitude = Double.parseDouble(parts[0]);
            double longitude = Double.parseDouble(parts[1]);
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                return null;
            }
            return new Location("纬度" + latitude + "、经度" + longitude,
                    "", "", latitude, longitude, "auto");
        } catch (NumberFormatException e) {
            // 不是数字，说明用户输入的是城市名，交给地理编码处理
            return null;
        }
    }

    /**
     * 统一的 GET 请求，返回响应体字符串
     */
    private String get(String url) throws IOException {
        Request request = new Request.Builder()
                .get()
                .url(url)
                .build();

        // 发送请求，使用完后自动关闭
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("天气接口请求失败，HTTP 状态码：" + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("天气接口没有返回内容");
            }
            return body.string();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    /**
     * 一个候选地点：同一个 id 可能同时出现在中文索引和英文索引里
     */
    private record Candidate(JsonNode english, JsonNode chinese) {

        Candidate(JsonNode node, String language) {
            this(LANGUAGE_EN.equals(language) ? node : null,
                    LANGUAGE_ZH.equals(language) ? node : null);
        }

        Candidate merge(Candidate other) {
            return new Candidate(
                    english != null ? english : other.english,
                    chinese != null ? chinese : other.chinese);
        }

        /**
         * 坐标、时区、人口以拉丁索引为准
         */
        JsonNode base() {
            return english != null ? english : chinese;
        }

        /**
         * 打分：精确匹配优先，其次比人口
         */
        long score(String keyword) {
            boolean exact = matches(english, keyword) || matches(chinese, keyword);
            long bonus = exact ? EXACT_NAME_BONUS : 0L;
            return bonus + base().path("population").asLong(0L);
        }

        private static boolean matches(JsonNode node, String keyword) {
            return node != null
                    && normalize(node.path("name").asText("")).equals(normalize(keyword));
        }
    }

    /**
     * 一个地点的查询条件
     */
    private record Location(String name, String admin1, String country,
                            double latitude, double longitude, String timezone) {

        /**
         * 完整地名，例如“中国 江苏 南京”；省份已经包含城市名时就不重复显示
         */
        String fullName() {
            StringBuilder text = new StringBuilder();
            append(text, country);
            if (!admin1.isBlank() && !admin1.contains(name)) {
                append(text, admin1);
            }
            append(text, name);
            return text.toString();
        }

        private static void append(StringBuilder text, String part) {
            if (part == null || part.isBlank()) {
                return;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(part);
        }
    }

    /**
     * 常用国外城市的别名表：键是用户可能输入的简体/繁体中文名，
     * 值是地理编码接口里命中率最高的英文名。
     * 想支持更多城市，在这里加一行就行。
     */
    private static final Map<String, String> CITY_ALIASES = buildCityAliases();

    private static Map<String, String> buildCityAliases() {
        Map<String, String> aliases = new HashMap<>();

        addAlias(aliases, "New York", "纽约", "紐約");
        addAlias(aliases, "London", "伦敦", "倫敦");
        addAlias(aliases, "Paris", "巴黎");
        addAlias(aliases, "Tokyo", "东京", "東京");
        addAlias(aliases, "Osaka", "大阪");
        addAlias(aliases, "Seoul", "首尔", "首爾");
        addAlias(aliases, "Singapore", "新加坡");
        addAlias(aliases, "Bangkok", "曼谷");
        addAlias(aliases, "Kuala Lumpur", "吉隆坡");
        addAlias(aliases, "Jakarta", "雅加达", "雅加達");
        addAlias(aliases, "Manila", "马尼拉", "馬尼拉");
        addAlias(aliases, "Dubai", "迪拜", "杜拜");
        addAlias(aliases, "Mumbai", "孟买", "孟買");
        addAlias(aliases, "New Delhi", "新德里");
        addAlias(aliases, "Sydney", "悉尼", "雪梨");
        addAlias(aliases, "Melbourne", "墨尔本", "墨爾本");
        addAlias(aliases, "Brisbane", "布里斯班");
        addAlias(aliases, "Auckland", "奥克兰", "奧克蘭");
        addAlias(aliases, "Los Angeles", "洛杉矶", "洛杉磯");
        addAlias(aliases, "San Francisco", "旧金山", "舊金山");
        addAlias(aliases, "Chicago", "芝加哥");
        addAlias(aliases, "Seattle", "西雅图", "西雅圖");
        addAlias(aliases, "Boston", "波士顿", "波士頓");
        addAlias(aliases, "Washington", "华盛顿", "華盛頓");
        addAlias(aliases, "Toronto", "多伦多", "多倫多");
        addAlias(aliases, "Vancouver", "温哥华", "溫哥華");
        addAlias(aliases, "Mexico City", "墨西哥城");
        addAlias(aliases, "Sao Paulo", "圣保罗", "聖保羅");
        addAlias(aliases, "Rio de Janeiro", "里约热内卢", "里約熱內盧");
        addAlias(aliases, "Buenos Aires", "布宜诺斯艾利斯");
        addAlias(aliases, "Berlin", "柏林");
        addAlias(aliases, "Munich", "慕尼黑");
        addAlias(aliases, "Frankfurt", "法兰克福", "法蘭克福");
        addAlias(aliases, "Rome", "罗马", "羅馬");
        addAlias(aliases, "Milan", "米兰", "米蘭");
        addAlias(aliases, "Madrid", "马德里", "馬德里");
        addAlias(aliases, "Barcelona", "巴塞罗那", "巴塞隆納");
        addAlias(aliases, "Amsterdam", "阿姆斯特丹");
        addAlias(aliases, "Vienna", "维也纳", "維也納");
        addAlias(aliases, "Zurich", "苏黎世", "蘇黎世");
        addAlias(aliases, "Stockholm", "斯德哥尔摩", "斯德哥爾摩");
        addAlias(aliases, "Istanbul", "伊斯坦布尔", "伊斯坦堡");
        addAlias(aliases, "Moscow", "莫斯科");
        addAlias(aliases, "Saint Petersburg", "圣彼得堡", "聖彼得堡");
        addAlias(aliases, "Cairo", "开罗", "開羅");
        addAlias(aliases, "Nairobi", "内罗毕", "內羅畢");
        addAlias(aliases, "Johannesburg", "约翰内斯堡", "約翰內斯堡");

        return Map.copyOf(aliases);
    }

    private static void addAlias(Map<String, String> aliases, String searchTerm, String... names) {
        for (String name : names) {
            aliases.put(name, searchTerm);
        }
    }
}