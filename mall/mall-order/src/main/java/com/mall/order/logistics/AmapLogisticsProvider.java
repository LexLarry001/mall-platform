package com.mall.order.logistics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 高德物流提供商（{@code mall.logistics.provider=amap} 时装配）。
 *
 * <p>发货时写一个 {@code ROUTE} 节点（折线 + 起点坐标）与一个 {@code SHIPPED} 状态节点；
 * 商家推进状态时按状态比例沿折线插值出当前位置，写入 {@code STATUS} 节点（含经纬度），
 * 前端即可在地图上渲染路线与当前位置。</p>
 *
 * <p>比例：SHIPPED=0、IN_TRANSIT=50%、OUT_FOR_DELIVERY=85%、DELIVERED=100%。
 * 若发货时路线不可用（降级），只写纯文本状态节点，与手动物流行为一致。</p>
 */
@Component
@ConditionalOnProperty(name = "mall.logistics.provider", havingValue = "amap")
public class AmapLogisticsProvider implements LogisticsProvider {

    private static final Logger log = LoggerFactory.getLogger(AmapLogisticsProvider.class);

    private final LogisticsTraceRepository traceRepository;
    private final AmapClient amapClient;

    public AmapLogisticsProvider(LogisticsTraceRepository traceRepository, AmapClient amapClient) {
        this.traceRepository = traceRepository;
        this.amapClient = amapClient;
    }

    @Override
    public RoutePlan planRoute(String fromAddress, String toAddress) {
        return amapClient.driving(fromAddress, toAddress);
    }

    @Override
    public void createTrace(String shipmentNo, String desc, RoutePlan route) {
        if (route == null || !route.available() || route.polyline().isEmpty()) {
            insertStatus(shipmentNo, LogisticsStatus.SHIPPED, desc, null, null, null);
            return;
        }
        try {
            LogisticsTraceDoc routeDoc = new LogisticsTraceDoc();
            routeDoc.setShipmentNo(shipmentNo);
            routeDoc.setNodeType(LogisticsTraceDoc.NODE_ROUTE);
            routeDoc.setStatus(LogisticsStatus.SHIPPED);
            routeDoc.setDesc("配送路线已规划：全程 " + formatDistance(route.distanceMeters())
                    + "，预计 " + formatDuration(route.durationSeconds()));
            routeDoc.setAddress(route.fromAddress());
            routeDoc.setLng(toDecimal(route.fromLng()));
            routeDoc.setLat(toDecimal(route.fromLat()));
            routeDoc.setPolyline(route.polyline());
            routeDoc.setTs(LocalDateTime.now());
            traceRepository.insert(routeDoc);

            insertStatus(shipmentNo, LogisticsStatus.SHIPPED, desc,
                    route.fromLng(), route.fromLat(), route.fromAddress());
        } catch (Exception e) {
            // MongoDB 写入失败不能影响发货主流程（与 M5 行为保持一致）
            log.warn("高德轨迹写入失败，降级为纯文本节点 shipmentNo={} : {}", shipmentNo, e.toString());
            insertStatus(shipmentNo, LogisticsStatus.SHIPPED, desc, null, null, null);
        }
    }

    @Override
    public void pushStatus(String shipmentNo, String nextStatus, String desc) {
        List<double[]> polyline = loadRoutePolyline(shipmentNo);
        double[] point = pointAt(polyline, ratio(nextStatus));
        if (point == null) {
            insertStatus(shipmentNo, nextStatus, desc, null, null, null);
            return;
        }
        insertStatus(shipmentNo, nextStatus, desc, point[0], point[1], null);
    }

    @Override
    public List<LogisticsTraceDoc> queryTrace(String shipmentNo) {
        return traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo);
    }

    /** 取最近一次 ROUTE 节点的折线；没有则返回空列表。 */
    private List<double[]> loadRoutePolyline(String shipmentNo) {
        return traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo).stream()
                .filter(doc -> LogisticsTraceDoc.NODE_ROUTE.equals(doc.getNodeType()))
                .map(LogisticsTraceDoc::getPolyline)
                .filter(points -> points != null && !points.isEmpty())
                .findFirst()
                .orElse(List.of());
    }

    private void insertStatus(String shipmentNo, String status, String desc,
                              Double lng, Double lat, String address) {
        LogisticsTraceDoc trace = new LogisticsTraceDoc();
        trace.setShipmentNo(shipmentNo);
        trace.setNodeType(LogisticsTraceDoc.NODE_STATUS);
        trace.setStatus(status);
        trace.setDesc(desc);
        trace.setAddress(address);
        if (lng != null) {
            trace.setLng(toDecimal(lng));
        }
        if (lat != null) {
            trace.setLat(toDecimal(lat));
        }
        trace.setTs(LocalDateTime.now());
        traceRepository.insert(trace);
    }

    /** 状态 → 行程比例。 */
    static double ratio(String status) {
        return switch (status) {
            case LogisticsStatus.IN_TRANSIT -> 0.5;
            case LogisticsStatus.OUT_FOR_DELIVERY -> 0.85;
            case LogisticsStatus.DELIVERED -> 1.0;
            default -> 0.0;
        };
    }

    /**
     * 按累计长度比例取折线上的点。
     *
     * @param polyline 折线坐标（[lng, lat]）
     * @param ratio    0~1
     * @return 插值点；折线为空返回 null
     */
    static double[] pointAt(List<double[]> polyline, double ratio) {
        if (polyline == null || polyline.isEmpty()) {
            return null;
        }
        if (polyline.size() == 1) {
            return polyline.get(0);
        }
        double clamped = Math.max(0, Math.min(1, ratio));
        double[] lengths = new double[polyline.size() - 1];
        double total = 0;
        for (int i = 1; i < polyline.size(); i++) {
            lengths[i - 1] = distance(polyline.get(i - 1), polyline.get(i));
            total += lengths[i - 1];
        }
        if (total <= 0) {
            return polyline.get(0);
        }
        double target = total * clamped;
        double accumulated = 0;
        for (int i = 0; i < lengths.length; i++) {
            if (accumulated + lengths[i] >= target) {
                double segRatio = lengths[i] <= 0 ? 0 : (target - accumulated) / lengths[i];
                double[] start = polyline.get(i);
                double[] end = polyline.get(i + 1);
                return new double[]{
                        start[0] + (end[0] - start[0]) * segRatio,
                        start[1] + (end[1] - start[1]) * segRatio};
            }
            accumulated += lengths[i];
        }
        return polyline.get(polyline.size() - 1);
    }

    private static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static BigDecimal toDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static String formatDistance(int meters) {
        return meters >= 1000 ? String.format("%.1f 公里", meters / 1000.0) : meters + " 米";
    }

    private static String formatDuration(int seconds) {
        return seconds >= 60 ? (seconds / 60) + " 分钟" : seconds + " 秒";
    }
}
