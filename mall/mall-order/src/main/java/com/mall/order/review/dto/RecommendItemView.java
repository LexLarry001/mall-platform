package com.mall.order.review.dto;

import java.math.BigDecimal;
import java.util.List;

/** 推荐结果中的单个商品（含推荐理由与评价证据）。 */
public record RecommendItemView(
        Long spuId,
        String name,
        String mainImg,
        BigDecimal price,
        BigDecimal avgRating,
        Integer commentCount,
        BigDecimal score,
        String reason,
        List<String> evidence) {
}
