package com.mall.order.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M5 Task 1：物流提供商纯单元测试（mock MongoDB Repository，验证 Provider 调用链路与状态流转）。
 */
@ExtendWith(MockitoExtension.class)
class LogisticsProviderTest {

    @Mock
    private LogisticsTraceRepository repository;

    @Captor
    private ArgumentCaptor<LogisticsTraceDoc> traceCaptor;

    private LogisticsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ManualLogisticsProvider(repository);
    }

    @Test
    void createTrace_inserts_shipped_node() {
        String shipmentNo = "SH_123";

        provider.createTrace(shipmentNo, "包裹已交给圆通快递", null);

        verify(repository).insert(traceCaptor.capture());
        LogisticsTraceDoc doc = traceCaptor.getValue();
        assertThat(doc.getShipmentNo()).isEqualTo(shipmentNo);
        assertThat(doc.getNodeType()).isEqualTo(LogisticsTraceDoc.NODE_STATUS);
        assertThat(doc.getStatus()).isEqualTo(LogisticsStatus.SHIPPED);
        assertThat(doc.getDesc()).isEqualTo("包裹已交给圆通快递");
        assertThat(doc.getTs()).isNotNull();
        assertThat(doc.getPolyline()).isNull();
    }

    @Test
    void planRoute_is_unavailable_in_manual_mode() {
        RoutePlan plan = provider.planRoute("上海市浦东新区世纪大道 100 号", "上海市徐汇区漕溪北路 200 号");

        assertThat(plan.available()).isFalse();
        assertThat(plan.polyline()).isEmpty();
        assertThat(plan.fromAddress()).isEqualTo("上海市浦东新区世纪大道 100 号");
        assertThat(plan.toAddress()).isEqualTo("上海市徐汇区漕溪北路 200 号");
    }

    @Test
    void pushStatus_inserts_next_step_node() {
        String shipmentNo = "SH_456";

        provider.pushStatus(shipmentNo, LogisticsStatus.IN_TRANSIT, "包裹到达上海分拣中心");

        verify(repository).insert(traceCaptor.capture());
        LogisticsTraceDoc doc = traceCaptor.getValue();
        assertThat(doc.getShipmentNo()).isEqualTo(shipmentNo);
        assertThat(doc.getStatus()).isEqualTo(LogisticsStatus.IN_TRANSIT);
        assertThat(doc.getDesc()).isEqualTo("包裹到达上海分拣中心");
    }

    @Test
    void queryTrace_falls_through_to_repository() {
        String shipmentNo = "SH_789";
        when(repository.findByShipmentNoOrderByTsDesc(shipmentNo))
                .thenReturn(java.util.List.of());

        assertThat(provider.queryTrace(shipmentNo)).isEmpty();
        verify(repository).findByShipmentNoOrderByTsDesc(shipmentNo);
    }

    @Test
    void logistics_status_flow_chinese_and_next() {
        assertThat(LogisticsStatus.next(LogisticsStatus.SHIPPED)).isEqualTo(LogisticsStatus.IN_TRANSIT);
        assertThat(LogisticsStatus.next(LogisticsStatus.IN_TRANSIT)).isEqualTo(LogisticsStatus.OUT_FOR_DELIVERY);
        assertThat(LogisticsStatus.next(LogisticsStatus.OUT_FOR_DELIVERY)).isEqualTo(LogisticsStatus.DELIVERED);
        assertThat(LogisticsStatus.next(LogisticsStatus.DELIVERED)).isNull();

        assertThat(LogisticsStatus.chinese(LogisticsStatus.SHIPPED)).isEqualTo("已发货");
        assertThat(LogisticsStatus.chinese(LogisticsStatus.IN_TRANSIT)).isEqualTo("运输中");
        assertThat(LogisticsStatus.chinese(LogisticsStatus.OUT_FOR_DELIVERY)).isEqualTo("派送中");
        assertThat(LogisticsStatus.chinese(LogisticsStatus.DELIVERED)).isEqualTo("已签收");
    }

    @Test
    void trace_doc_has_correct_mongo_collection() {
        var ann = LogisticsTraceDoc.class.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo("logistics_trace");
    }
}
