package com.mall.order.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 商家回复评价请求。 */
public record ReviewReplyRequest(
        @NotBlank(message = "回复内容不能为空")
        @Size(min = 2, max = 300, message = "回复内容需 2~300 字") String reply) {
}
