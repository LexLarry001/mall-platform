package com.mall.order.logistics;

import java.util.List;

/**
 * 物流提供商接口（规格 §5.7「为高德预留的服务接口口子」）。
 *
 * <p>两个实现按 {@code mall.logistics.provider} 条件装配：
 * {@code manual}（默认，手动模式）与 {@code amap}（M7 高德可视化）。
 * 发货与订单业务代码只依赖此接口，切换实现零改动（硬约束：物流域与业务解耦）。</p>
 *
 * <p>Provider **只负责路线规划与写 MongoDB 轨迹文档**。MySQL shipment 的创建/更新、
 * 子订单 status 变更等业务操作由外层 {@code MerchantLogisticsService} 负责（事务边界控制）。</p>
 */
public interface LogisticsProvider {

    /**
     * 规划配送路线（高德模式返回可用路线；manual 模式返回 {@link RoutePlan#unavailable}）。
     *
     * @param fromAddress 发货地址（商家填写）
     * @param toAddress   收货地址（主订单快照）
     */
    RoutePlan planRoute(String fromAddress, String toAddress);

    /**
     * 写"已发货"节点（商家发货时调用一次）。
     *
     * @param shipmentNo 发货单号（由外层 Service 先生成并写入 MySQL shipment）
     * @param desc       人类可读描述，如"包裹已交给圆通快递"
     * @param route      路线规划结果；不可用时仅写状态节点（降级手动物流）
     */
    void createTrace(String shipmentNo, String desc, RoutePlan route);

    /**
     * 写状态推进节点（商家每次手动推进时调用一次）。
     *
     * @param shipmentNo 发货单号
     * @param nextStatus 下一状态（LogisticsStatus.*）
     * @param desc       人类可读描述，如"包裹到达上海浦东分拣中心"
     */
    void pushStatus(String shipmentNo, String nextStatus, String desc);

    /** 查全部轨迹节点（时间倒序，最新在前）。 */
    List<LogisticsTraceDoc> queryTrace(String shipmentNo);
}
