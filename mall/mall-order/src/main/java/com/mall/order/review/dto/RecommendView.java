package com.mall.order.review.dto;

import java.util.List;

/**
 * AI 推荐结果。
 *
 * @param source ai：DeepSeek 重排；rule：规则降级
 */
public record RecommendView(
        String source,
        RecommendParsed parsed,
        String summary,
        List<RecommendItemView> items) {
}
