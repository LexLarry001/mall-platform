package com.mall.order.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单列表摘要视图：主订单字段 + 跨子订单聚合的订单项（列表页缩略展示用）。
 */
public record OrderSummaryView(
        String orderNo,
        Integer status,
        BigDecimal totalAmount,
        BigDecimal payAmount,
        LocalDateTime createTime,
        LocalDateTime expireTime,
        List<OrderItemView> items) {
}
