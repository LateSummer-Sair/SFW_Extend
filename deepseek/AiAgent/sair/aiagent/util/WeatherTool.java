package sair.aiagent.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import sair.aiagent.core.RedisClient;

/**
 * 天气查询工具 —— 使用免费的 Open-Meteo API（无需 API Key）。
 * <p>
 * 两个步骤：地理编码（城市名 -> 经纬度） + 天气预报（经纬度 -> 天气数据）。
 * 所有通道均可通过 {@link #queryWeather(String)} 方法调用。
 * </p>
 *
 * <h3>API 说明</h3>
 * <ul>
 *   <li>Geocoding: {@code https://geocoding-api.open-meteo.com/v1/search?name=城市&count=1&language=zh}</li>
 *   <li>Forecast:  {@code https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current=...&daily=...&timezone=auto}</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * String result = WeatherTool.queryWeather("北京");
 * System.out.println(result);
 * }</pre>
 */
public class WeatherTool {

    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL   = "https://api.open-meteo.com/v1/forecast";

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT    = 15_000;

    /** 天气代码 -> 中文描述（Open-Meteo WMO codes） */
    private static String weatherCodeToChinese(int code) {
        switch (code) {
            case 0:  return "晴朗";
            case 1:  return "大部晴朗";
            case 2:  return "多云";
            case 3:  return "阴天";
            case 45: case 48: return "雾";
            case 51: return "小毛毛雨";
            case 53: return "中毛毛雨";
            case 55: return "大毛毛雨";
            case 56: case 57: return "冻毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 66: case 67: return "冻雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 77: return "雪粒";
            case 80: return "阵雨";
            case 81: return "中阵雨";
            case 82: return "大阵雨";
            case 85: return "小阵雪";
            case 86: return "大阵雪";
            case 95: return "雷暴";
            case 96: case 99: return "雷暴+冰雹";
            default: return "未知";
        }
    }

    /**
     * 查询指定城市的天气。
     *
     * @param city 城市名（中文或英文，如 "北京"、"Shanghai"、"东京"）
     * @return 格式化的天气信息字符串，失败返回错误描述
     */
    public static String queryWeather(String city) {
        if (city == null || city.trim().isEmpty()) {
            return "[weather] 请提供城市名，例如：<weather>北京</weather>";
        }
        city = city.trim();

        // Redis 旁路缓存：同一城市 1 小时内天气结果基本不变（Redis 未运行则静默降级）
        RedisClient redis = RedisClient.getInstance();
        String cacheKey = "weather:" + city;
        if (redis != null) {
            String cached = redis.get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        try {
            // Step 1: 地理编码
            double[] coords = geocode(city);
            if (coords == null) {
                return "[weather] 未找到城市「" + city + "」，请检查城市名称拼写是否正确";
            }

            // Step 2: 天气预报
            String result = forecast(coords[0], coords[1], city);

            // 仅缓存成功结果（错误信息以 [weather] 开头，不缓存），TTL 1 小时
            if (redis != null && result != null && !result.startsWith("[weather]")) {
                redis.setex(cacheKey, 3600, result);
            }
            return result;

        } catch (Exception e) {
            return "[weather] 查询失败: " + e.getMessage();
        }
    }

    /**
     * 地理编码：城市名 -> [lat, lon]。
     */
    private static double[] geocode(String city) throws Exception {
        String encoded = URLEncoder.encode(city, "UTF-8");
        String urlStr = GEOCODING_URL + "?name=" + encoded + "&count=1&language=zh&format=json";

        String json = httpGet(urlStr);
        if (json == null || json.isEmpty()) return null;

        // 简单 JSON 解析（不依赖 Gson，减少依赖）
        if (!json.contains("\"results\"")) return null;

        double lat = extractDouble(json, "\"latitude\"");
        double lon = extractDouble(json, "\"longitude\"");

        if (lat == Double.MIN_VALUE || lon == Double.MIN_VALUE) return null;
        return new double[]{lat, lon};
    }

    /**
     * 天气预报：经纬度 -> 天气信息。
     */
    private static String forecast(double lat, double lon, String city) throws Exception {
        String urlStr = FORECAST_URL
                + "?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max"
                + "&timezone=auto"
                + "&forecast_days=4";

        String json = httpGet(urlStr);
        if (json == null || json.isEmpty()) {
            return "[weather] 获取天气数据失败，请稍后重试";
        }

        if (!json.contains("\"current\"")) {
            return "[weather] 天气数据解析失败";
        }

        // 当前天气
        double currentTemp    = extractDouble(json, "\"temperature_2m\"");
        int    currentHumidity = (int) extractDouble(json, "\"relative_humidity_2m\"");
        double currentWind    = extractDouble(json, "\"wind_speed_10m\"");
        int    currentCode    = (int) extractDouble(json, "\"weather_code\"");

        StringBuilder sb = new StringBuilder();
        sb.append("[天气] ").append(city).append("当前天气：\n");
        sb.append("温度：").append(String.format("%.1f", currentTemp)).append("°C\n");
        sb.append("天气：").append(weatherCodeToChinese(currentCode)).append("\n");
        sb.append("湿度：").append(currentHumidity).append("%\n");
        sb.append("风速：").append(String.format("%.1f", currentWind)).append(" km/h\n");

        // 未来预报（提取 daily 数组）
        sb.append("\n未来3天预报：\n");
        String[] maxTemps = extractDoubleArray(json, "\"temperature_2m_max\"");
        String[] minTemps = extractDoubleArray(json, "\"temperature_2m_min\"");
        String[] codes    = extractIntArray(json, "\"weather_code\"");
        String[] precips  = extractIntArray(json, "\"precipitation_probability_max\"");
        String[] dates    = extractStringArray(json, "\"time\"");

        int days = Math.min(4, Math.min(
                Math.min(maxTemps != null ? maxTemps.length : 0,
                         minTemps != null ? minTemps.length : 0),
                Math.min(codes != null ? codes.length : 0,
                         dates != null ? dates.length : 0)));

        // 跳过第0天（当天），展示未来3天
        for (int i = 1; i < days; i++) {
            String date = dates != null && i < dates.length ? dates[i] : "?";
            double maxT = maxTemps != null && i < maxTemps.length ? parseDouble(maxTemps[i]) : 0;
            double minT = minTemps != null && i < minTemps.length ? parseDouble(minTemps[i]) : 0;
            int code = codes != null && i < codes.length ? parseInt(codes[i]) : -1;
            int precip = precips != null && i < precips.length ? parseInt(precips[i]) : -1;

            sb.append(date).append("：");
            sb.append(weatherCodeToChinese(code)).append("，");
            sb.append(String.format("%.0f", minT)).append("~").append(String.format("%.0f", maxT)).append("°C");
            if (precip >= 0) sb.append("，降水概率").append(precip).append("%");
            sb.append("\n");
        }

        sb.append("\n数据来源：Open-Meteo (open-meteo.com)");
        return sb.toString();
    }

    // ==================== HTTP 工具 ====================

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "AiAgent/2.8 WeatherTool");
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        if (status != 200) {
            conn.disconnect();
            return null;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    // ==================== 简易 JSON 解析（无第三方依赖） ====================

    private static double extractDouble(String json, String key) {
        String search = key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return Double.MIN_VALUE;
        idx += search.length();
        // 跳过空格
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        if (end == idx) return Double.MIN_VALUE;
        try {
            return Double.parseDouble(json.substring(idx, end));
        } catch (NumberFormatException e) {
            return Double.MIN_VALUE;
        }
    }

    private static String[] extractDoubleArray(String json, String key) {
        String search = key + "\":[";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        int end = json.indexOf("]", idx);
        if (end < 0) return null;
        return json.substring(idx, end).split(",");
    }

    private static String[] extractIntArray(String json, String key) {
        return extractDoubleArray(json, key); // 复用，数字格式兼容
    }

    private static String[] extractStringArray(String json, String key) {
        String search = key + "\":[";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        int end = json.indexOf("]", idx);
        if (end < 0) return null;
        String content = json.substring(idx, end);
        // 去除引号
        return content.replace("\"", "").split(",");
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }
}
