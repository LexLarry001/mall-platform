package com.mall.order.cart.dto;

import jakarta.validation.constraints.Min;

/**
 * 购物车条目修改请求：quantity / checked 至少给一个，缺省字段保持原值。
 */
public record CartUpdateRequest(
        @Min(value = 1, message = "数量至少为 1") Integer quantity,
        Boolean checked) {
}
