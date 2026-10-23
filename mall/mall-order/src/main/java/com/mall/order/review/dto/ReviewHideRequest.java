package com.mall.order.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 平台隐藏评价请求。 */
public record ReviewHideRequest(
        @NotBlank(message = "隐藏原因不能为空")
        @Size(max = 200, message = "隐藏原因最多 200 字") String reason) {
}
