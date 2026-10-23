package com.mall.order.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 子订单视图（一店一单）。
 */
public record SubOrderView(
        String subOrderNo,
        Long shopId,
        String shopName,
        BigDecimal amount,
        Integer status,
        List<OrderItemView> items) {
}
