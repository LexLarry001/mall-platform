package com.mall.order.order.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 提交订单请求。
 *
 * @param addressId 收货地址 ID（须属于当前登录买家）
 */
public record OrderSubmitRequest(
        @NotNull(message = "收货地址不能为空") Long addressId) {
}
