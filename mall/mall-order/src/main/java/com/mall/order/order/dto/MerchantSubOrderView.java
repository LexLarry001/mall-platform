package com.mall.order.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家订单视图（按店铺维度看子订单）。
 *
 * <p>聚合买家收货快照、订单项快照与发货单最新状态，供商家工作台一张表完成
 * 发货 / 物流推进 / 轨迹查看。发货单未创建时 shipment 相关字段为 null。</p>
 */
public record MerchantSubOrderView(
        String subOrderNo,
        String orderNo,
        Integer status,
        BigDecimal amount,
        LocalDateTime createTime,
        String receiver,
        String phone,
        String fullAddress,
        List<OrderItemView> items,
        String shipmentNo,
        String carrierName,
        String trackingNo,
        String logisticsStatus,
        Integer traceCount) {
}
