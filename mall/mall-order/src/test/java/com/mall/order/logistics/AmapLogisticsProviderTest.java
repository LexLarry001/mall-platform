package com.mall.order.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M7 Task 2：高德物流提供商单元测试（mock AmapClient + MongoDB Repository）。
 *
 * <p>覆盖：路线规划委托、编码失败降级、ROUTE + SHIPPED 双节点、折线解析、
 * 四个状态插值点。</p>
 */
@ExtendWith(MockitoExtension.class)
class AmapLogisticsProviderTest {

    private static final String FROM = "上海市浦东新区世纪大道 100 号";
    private static final String TO = "上海市徐汇区漕溪北路 200 号";

    @Mock
    private LogisticsTraceRepository repository;

    @Mock
    private AmapClient amapClient;

    @Captor
    private ArgumentCaptor<LogisticsTraceDoc> docCaptor;

    private AmapLogisticsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AmapLogisticsProvider(repository, amapClient);
    }

    private static RoutePlan sampleRoute() {
        return new RoutePlan(121.0, 31.0, 121.1, 31.1,
                List.of(new double[]{121.0, 31.0},
                        new double[]{121.05, 31.05},
                        new double[]{121.1, 31.1}),
                10000, 1200, FROM, TO, true);
    }

    private static LogisticsTraceDoc routeDoc() {
        LogisticsTraceDoc doc = new LogisticsTraceDoc();
        doc.setNodeType(LogisticsTraceDoc.NODE_ROUTE);
        doc.setPolyline(sampleRoute().polyline());
        doc.setTs(LocalDateTime.now());
        return doc;
    }

    @Test
    void planRoute_delegates_to_amap_client() {
        when(amapClient.driving(FROM, TO)).thenReturn(sampleRoute());

        RoutePlan plan = provider.planRoute(FROM, TO);

        assertThat(plan.available()).isTrue();
        assertThat(plan.polyline()).hasSize(3);
        assertThat(plan.distanceMeters()).isEqualTo(10000);
    }

    @Test
    void createTrace_writes_route_node_then_shipped_status() {
        provider.createTrace("SH_1", "包裹已交给圆通快递", sampleRoute());

        verify(repository, times(2)).insert(docCaptor.capture());
        List<LogisticsTraceDoc> docs = docCaptor.getAllValues();

        LogisticsTraceDoc route = docs.get(0);
        assertThat(route.getNodeType()).isEqualTo(LogisticsTraceDoc.NODE_ROUTE);
        assertThat(route.getPolyline()).hasSize(3);
        assertThat(route.getAddress()).isEqualTo(FROM);
        assertThat(route.getDesc()).contains("10.0 公里").contains("20 分钟");

        LogisticsTraceDoc shipped = docs.get(1);
        assertThat(shipped.getNodeType()).isEqualTo(LogisticsTraceDoc.NODE_STATUS);
        assertThat(shipped.getStatus()).isEqualTo(LogisticsStatus.SHIPPED);
        assertThat(shipped.getDesc()).isEqualTo("包裹已交给圆通快递");
        assertThat(shipped.getLng()).isEqualByComparingTo("121.000000");
        assertThat(shipped.getLat()).isEqualByComparingTo("31.000000");
    }

    @Test
    void createTrace_without_route_degrades_to_text_only() {
        RoutePlan unavailable = RoutePlan.unavailable(FROM, TO);

        provider.createTrace("SH_2", "包裹已交给圆通快递", unavailable);

        verify(repository, times(1)).insert(docCaptor.capture());
        LogisticsTraceDoc doc = docCaptor.getValue();
        assertThat(doc.getNodeType()).isEqualTo(LogisticsTraceDoc.NODE_STATUS);
        assertThat(doc.getStatus()).isEqualTo(LogisticsStatus.SHIPPED);
        assertThat(doc.getPolyline()).isNull();
        assertThat(doc.getLng()).isNull();
    }

    @Test
    void createTrace_mongo_failure_falls_back_to_text_only() {
        when(repository.insert(org.mockito.ArgumentMatchers.any(LogisticsTraceDoc.class)))
                .thenThrow(new RuntimeException("mongo down"))
                .thenReturn(new LogisticsTraceDoc());

        provider.createTrace("SH_3", "包裹已交给圆通快递", sampleRoute());

        verify(repository, times(2)).insert(docCaptor.capture());
        LogisticsTraceDoc fallback = docCaptor.getAllValues().get(1);
        assertThat(fallback.getNodeType()).isEqualTo(LogisticsTraceDoc.NODE_STATUS);
        assertThat(fallback.getLng()).isNull();
    }

    @Test
    void pushStatus_writes_positions_at_status_ratios() {
        when(repository.findByShipmentNoOrderByTsDesc("SH_4")).thenReturn(List.of(routeDoc()));

        provider.pushStatus("SH_4", LogisticsStatus.IN_TRANSIT, "运输中");
        provider.pushStatus("SH_4", LogisticsStatus.OUT_FOR_DELIVERY, "派送中");
        provider.pushStatus("SH_4", LogisticsStatus.DELIVERED, "已签收");

        verify(repository, times(3)).insert(docCaptor.capture());
        List<LogisticsTraceDoc> docs = docCaptor.getAllValues();

        assertThat(docs.get(0).getLng()).isEqualByComparingTo("121.050000");
        assertThat(docs.get(0).getLat()).isEqualByComparingTo("31.050000");
        assertThat(docs.get(1).getLng()).isEqualByComparingTo("121.085000");
        assertThat(docs.get(1).getLat()).isEqualByComparingTo("31.085000");
        assertThat(docs.get(2).getLng()).isEqualByComparingTo("121.100000");
        assertThat(docs.get(2).getLat()).isEqualByComparingTo("31.100000");
    }

    @Test
    void pushStatus_without_route_writes_text_only() {
        when(repository.findByShipmentNoOrderByTsDesc("SH_5")).thenReturn(List.of());

        provider.pushStatus("SH_5", LogisticsStatus.IN_TRANSIT, "运输中");

        verify(repository).insert(docCaptor.capture());
        assertThat(docCaptor.getValue().getLng()).isNull();
        assertThat(docCaptor.getValue().getStatus()).isEqualTo(LogisticsStatus.IN_TRANSIT);
    }

    @Test
    void interpolation_covers_four_status_points() {
        List<double[]> polyline = sampleRoute().polyline();

        assertThat(AmapLogisticsProvider.ratio(LogisticsStatus.SHIPPED)).isEqualTo(0.0);
        assertThat(AmapLogisticsProvider.ratio(LogisticsStatus.IN_TRANSIT)).isEqualTo(0.5);
        assertThat(AmapLogisticsProvider.ratio(LogisticsStatus.OUT_FOR_DELIVERY)).isEqualTo(0.85);
        assertThat(AmapLogisticsProvider.ratio(LogisticsStatus.DELIVERED)).isEqualTo(1.0);

        double[] start = AmapLogisticsProvider.pointAt(polyline, 0.0);
        double[] middle = AmapLogisticsProvider.pointAt(polyline, 0.5);
        double[] nearEnd = AmapLogisticsProvider.pointAt(polyline, 0.85);
        double[] end = AmapLogisticsProvider.pointAt(polyline, 1.0);

        assertThat(start).containsExactly(121.0, 31.0);
        assertThat(middle[0]).isCloseTo(121.05, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(middle[1]).isCloseTo(31.05, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(nearEnd[0]).isCloseTo(121.085, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(nearEnd[1]).isCloseTo(31.085, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(end).containsExactly(121.1, 31.1);
    }

    @Test
    void parsePolyline_splits_lng_lat_pairs() {
        List<double[]> points = AmapClient.parsePolyline("121.1,31.1;121.2,31.2;bad;121.3,31.3");

        assertThat(points).hasSize(3);
        assertThat(points.get(0)).containsExactly(121.1, 31.1);
        assertThat(points.get(2)).containsExactly(121.3, 31.3);
    }

    @Test
    void queryTrace_delegates_to_repository() {
        when(repository.findByShipmentNoOrderByTsDesc(anyString())).thenReturn(List.of());

        assertThat(provider.queryTrace("SH_6")).isEmpty();
        verify(repository).findByShipmentNoOrderByTsDesc("SH_6");
    }
}
