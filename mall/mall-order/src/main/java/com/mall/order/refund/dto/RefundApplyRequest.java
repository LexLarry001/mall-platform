package com.mall.order.refund.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款申请入参（订单项粒度，整项退款）。
 *
 * @param orderNo       主订单号
 * @param subOrderNo    子订单号
 * @param orderItemId   订单项 ID
 * @param reason        退款原因（必填）
 * @param evidenceImages 凭证图 URL（最多 3 张，可空）
 */
public record RefundApplyRequest(
        String orderNo,
        String subOrderNo,
        Long orderItemId,
        String reason,
        List<String> evidenceImages) {
}
