package com.mall.order.refund.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款单视图（买家 / 商家共用；金额与状态均为最终值）。
 */
public record RefundView(
        String refundNo,
        String orderNo,
        String subOrderNo,
        Long orderItemId,
        Long spuId,
        Long skuId,
        String productName,
        Long shopId,
        Integer quantity,
        BigDecimal refundAmount,
        String reason,
        List<String> evidenceImages,
        Integer status,
        String auditRemark,
        LocalDateTime auditTime,
        LocalDateTime refundTime,
        String failMsg,
        LocalDateTime createTime) {
}
