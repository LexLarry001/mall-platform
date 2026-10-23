package com.mall.order.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 买家发表评价请求（M10）。
 *
 * @param orderItemId 已完成子订单中的订单项 ID
 * @param rating      1~5 星
 * @param content     评价正文 5~500 字
 * @param images      评价图 URL，最多 6 张
 * @param anonymous   是否匿名
 */
public record ReviewCreateRequest(
        @NotNull(message = "订单项不能为空") Long orderItemId,
        @NotNull(message = "评分不能为空") @Min(value = 1, message = "评分最低 1 星")
        @Max(value = 5, message = "评分最高 5 星") Integer rating,
        @NotBlank(message = "评价内容不能为空")
        @Size(min = 5, max = 500, message = "评价内容需 5~500 字") String content,
        @Size(max = 6, message = "评价图最多 6 张") List<String> images,
        Boolean anonymous) {
}
