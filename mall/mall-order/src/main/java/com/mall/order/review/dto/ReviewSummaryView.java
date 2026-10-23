package com.mall.order.review.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 评价摘要视图。
 *
 * @param source ai：DeepSeek 生成；stat：统计降级
 */
public record ReviewSummaryView(
        Long spuId,
        Integer reviewCount,
        BigDecimal avgRating,
        BigDecimal recommendRate,
        String summary,
        List<String> pros,
        List<String> cons,
        List<String> tags,
        String source,
        LocalDateTime updatedAt) {
}
