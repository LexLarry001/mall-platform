package com.mall.order.order.dto;

import java.math.BigDecimal;

/**
 * 订单项视图（快照字段直出；id 供退款申请定位订单项）。
 */
public record OrderItemView(
        Long itemId,
        Long spuId,
        Long skuId,
        String productName,
        String mainImg,
        String specJson,
        BigDecimal price,
        Integer quantity,
        Boolean reviewed) {
}
