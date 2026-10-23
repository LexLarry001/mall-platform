package com.mall.order.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 按评价推荐请求。
 *
 * @param query      自然语言需求，如「小户型客厅，想要不太软的沙发，预算 3000 以内」
 * @param budgetMax  预算上限（可选）
 * @param categoryId 类目（可选）
 * @param limit      返回条数，默认 5
 */
public record RecommendRequest(
        @NotBlank(message = "请描述您的需求") @Size(max = 200, message = "需求描述最多 200 字") String query,
        java.math.BigDecimal budgetMax,
        Long categoryId,
        Integer limit) {

    public int limitOrDefault() {
        if (limit == null || limit <= 0) return 5;
        return Math.min(limit, 10);
    }
}
