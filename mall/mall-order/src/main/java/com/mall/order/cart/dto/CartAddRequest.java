package com.mall.order.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 加购请求。同款（同 skuId）重复加购时数量累加合并。
 */
public record CartAddRequest(
        @NotNull(message = "商品 SKU 不能为空") Long skuId,
        @NotNull(message = "数量不能为空") @Min(value = 1, message = "数量至少为 1") Integer quantity) {
}
