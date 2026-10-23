package com.mall.order.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情视图：主订单（含地址快照）+ 子订单 + 订单项三级聚合。
 */
public record OrderDetailView(
        String orderNo,
        Integer status,
        BigDecimal totalAmount,
        BigDecimal payAmount,
        String receiver,
        String phone,
        String fullAddress,
        LocalDateTime createTime,
        LocalDateTime expireTime,
        List<SubOrderView> subOrders) {
}
