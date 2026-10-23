package com.mall.order.review.dto;

import java.math.BigDecimal;
import java.util.List;

/** 推荐请求的规则解析结果（用于前端回显与 Prompt）。 */
public record RecommendParsed(
        Long categoryId,
        BigDecimal budgetMax,
        List<String> keywords) {
}
