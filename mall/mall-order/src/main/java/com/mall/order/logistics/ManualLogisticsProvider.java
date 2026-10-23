package com.mall.order.logistics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 手动物流提供商（默认实现，{@code mall.logistics.provider=manual} 或未配置时装配）。
 *
 * <p>只负责写/读 MongoDB logistics_trace 文档，不调任何第三方 API，也不做路线规划。
 * 配置 {@code provider=amap} 时由 {@link AmapLogisticsProvider} 接管，业务代码零改动。</p>
 */
@Component
@ConditionalOnProperty(name = "mall.logistics.provider", havingValue = "manual", matchIfMissing = true)
public class ManualLogisticsProvider implements LogisticsProvider {

    private final LogisticsTraceRepository traceRepository;

    public ManualLogisticsProvider(LogisticsTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    @Override
    public RoutePlan planRoute(String fromAddress, String toAddress) {
        return RoutePlan.unavailable(fromAddress, toAddress);
    }

    @Override
    public void createTrace(String shipmentNo, String desc, RoutePlan route) {
        LogisticsTraceDoc trace = new LogisticsTraceDoc();
        trace.setShipmentNo(shipmentNo);
        trace.setNodeType(LogisticsTraceDoc.NODE_STATUS);
        trace.setStatus(LogisticsStatus.SHIPPED);
        trace.setDesc(desc);
        trace.setTs(LocalDateTime.now());
        traceRepository.insert(trace);
    }

    @Override
    public void pushStatus(String shipmentNo, String nextStatus, String desc) {
        LogisticsTraceDoc trace = new LogisticsTraceDoc();
        trace.setShipmentNo(shipmentNo);
        trace.setNodeType(LogisticsTraceDoc.NODE_STATUS);
        trace.setStatus(nextStatus);
        trace.setDesc(desc);
        trace.setTs(LocalDateTime.now());
        traceRepository.insert(trace);
    }

    @Override
    public List<LogisticsTraceDoc> queryTrace(String shipmentNo) {
        return traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo);
    }
}
