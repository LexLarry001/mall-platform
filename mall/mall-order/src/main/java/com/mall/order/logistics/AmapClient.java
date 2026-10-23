package com.mall.order.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 高德 Web 服务客户端（地理编码 + 驾车路径规划）。
 *
 * <p>仅后端使用，Key 从 {@code MALL_AMAP_WEB_KEY} 注入。所有失败（无 Key、超时、
 * status≠1、无结果）统一返回 {@code Optional.empty()} / {@link RoutePlan#unavailable}，
 * 只记 WARN 日志，绝不抛异常——发货链路不允许因地图服务失败而中断。</p>
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private final LogisticsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;

    public AmapClient(LogisticsProperties properties) {
        this.properties = properties;
        LogisticsProperties.Amap amap = properties.getAmap();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(amap.getTimeoutMs(), 500));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(amap.getBaseUrl())
                .build();
    }

    /** 地理编码结果：坐标 + 高德标准化地址 + 匹配级别。 */
    public record GeocodeResult(double lng, double lat, String formattedAddress, String level) {
    }

    /**
     * 地址 → 经纬度。
     *
     * @return {@code [lng, lat]}；地址为空、无 Key、无结果或调用失败时为空
     */
    public Optional<double[]> geocode(String address) {
        return geocodeDetail(address).map(r -> new double[]{r.lng(), r.lat()});
    }

    /**
     * 地址 → 详细地理编码结果（供地址校验接口展示标准化地址与匹配级别）。
     *
     * @return 无 Key、无结果或调用失败时为空
     */
    public Optional<GeocodeResult> geocodeDetail(String address) {
        String key = properties.getAmap().getKey();
        if (isBlank(address) || isBlank(key)) {
            return Optional.empty();
        }
        try {
            String body = restClient.get()
                    .uri(b -> b.path("/geocode/geo")
                            .queryParam("key", key)
                            .queryParam("address", address)
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!isOk(root)) {
                log.warn("高德地理编码返回失败 address={} info={} infocode={}",
                        address, root.path("info").asText(), root.path("infocode").asText());
                return Optional.empty();
            }
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                log.warn("高德地理编码无结果 address={}", address);
                return Optional.empty();
            }
            JsonNode first = geocodes.get(0);
            double[] point = parseLocation(first.path("location").asText(""));
            if (point == null) {
                log.warn("高德地理编码坐标格式异常 address={}", address);
                return Optional.empty();
            }
            return Optional.of(new GeocodeResult(point[0], point[1],
                    first.path("formatted_address").asText(""), first.path("level").asText("")));
        } catch (Exception e) {
            log.warn("高德地理编码调用异常 address={} : {}", address, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 驾车路径规划（起终点均走地理编码）。
     *
     * @return 可用路线；任一端编码失败或无路径时返回 {@link RoutePlan#unavailable}
     */
    public RoutePlan driving(String fromAddress, String toAddress) {
        Optional<double[]> from = geocode(fromAddress);
        Optional<double[]> to = geocode(toAddress);
        if (from.isEmpty() || to.isEmpty()) {
            return RoutePlan.unavailable(fromAddress, toAddress);
        }
        String key = properties.getAmap().getKey();
        try {
            double[] origin = from.get();
            double[] destination = to.get();
            String body = restClient.get()
                    .uri(b -> b.path("/direction/driving")
                            .queryParam("key", key)
                            .queryParam("origin", formatLocation(origin))
                            .queryParam("destination", formatLocation(destination))
                            .queryParam("strategy", 0)
                            .queryParam("extensions", "base")
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!isOk(root)) {
                log.warn("高德路径规划返回失败 from={} to={} info={}", fromAddress, toAddress,
                        root.path("info").asText());
                return RoutePlan.unavailable(fromAddress, toAddress);
            }
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                log.warn("高德路径规划无可用路线 from={} to={}", fromAddress, toAddress);
                return RoutePlan.unavailable(fromAddress, toAddress);
            }
            JsonNode path = paths.get(0);
            List<double[]> polyline = new ArrayList<>();
            for (JsonNode step : path.path("steps")) {
                polyline.addAll(parsePolyline(step.path("polyline").asText("")));
            }
            if (polyline.isEmpty()) {
                log.warn("高德路径规划折线为空 from={} to={}", fromAddress, toAddress);
                return RoutePlan.unavailable(fromAddress, toAddress);
            }
            return new RoutePlan(origin[0], origin[1], destination[0], destination[1], polyline,
                    path.path("distance").asInt(0), path.path("duration").asInt(0),
                    fromAddress, toAddress, true);
        } catch (Exception e) {
            log.warn("高德路径规划调用异常 from={} to={} : {}", fromAddress, toAddress, e.toString());
            return RoutePlan.unavailable(fromAddress, toAddress);
        }
    }

    /** 解析高德折线字符串 "lng,lat;lng,lat"（package-private 便于单测）。 */
    static List<double[]> parsePolyline(String polyline) {
        List<double[]> points = new ArrayList<>();
        if (polyline == null || polyline.isBlank()) {
            return points;
        }
        for (String pair : polyline.split(";")) {
            double[] point = parseLocation(pair);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    /** 解析 "lng,lat"，非法返回 null。 */
    static double[] parseLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 高德 status=1 表示成功。 */
    private static boolean isOk(JsonNode root) {
        return root != null && "1".equals(root.path("status").asText());
    }

    private static String formatLocation(double[] point) {
        return point[0] + "," + point[1];
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
