package com.mall.order.review.ai;

import com.mall.order.entity.ProductReviewEntity;

import java.math.BigDecimal;
import java.util.List;

/** 评价相关 Prompt 构造：只做文本拼装，便于单测与后续替换模型。 */
public final class ReviewPromptBuilder {

    private ReviewPromptBuilder() {
    }

    public static final String SUMMARY_SYSTEM = """
            你是家具商城的评价分析助手。只能依据用户提供的评价原文归纳，不得编造未出现的事实。
            输出严格 JSON，不要 Markdown 代码块，不要额外说明：
            {
              "summary": "80~120 字总体结论，指出优点与主要不足",
              "pros": ["2~4 条真实优点"],
              "cons": ["0~3 条真实不足，没有则返回空数组"],
              "tags": ["4~8 个短标签，每个不超过 6 个字"],
              "recommendRate": 0.0
            }
            recommendRate 取值 0~1，表示评价中 4 星及以上占比。
            """;

    public static String summaryUser(String productName, BigDecimal avgRating,
                                     int reviewCount, List<ProductReviewEntity> reviews) {
        StringBuilder sb = new StringBuilder();
        sb.append("商品：").append(productName)
                .append("\n平均星级：").append(avgRating == null ? "5.0" : avgRating)
                .append("\n评价数：").append(reviewCount)
                .append("\n评价原文：\n");
        for (ProductReviewEntity review : reviews) {
            sb.append("- ").append(review.getRating()).append("星：")
                    .append(truncate(review.getContent(), 200)).append('\n');
        }
        return sb.toString();
    }

    public static final String RECOMMEND_SYSTEM = """
            你是家具商城的导购。根据买家需求和候选商品及其真实评价，选出最合适的商品并排序。
            只能从给定的 spuId 中选择，推荐理由必须能在评价或商品参数中找到依据，禁止编造。
            输出严格 JSON，不要 Markdown 代码块，不要额外说明：
            {
              "summary": "一句话总体建议",
              "items": [
                {"spuId": 6001, "score": 0.9, "reason": "不超过 60 字的推荐理由", "evidence": ["评价原文片段"]}
              ]
            }
            最多返回指定数量的商品；没有合适商品时 items 返回空数组。
            """;

    public static String recommendUser(String query, String parsedDesc, String candidateText) {
        return "买家需求：" + query + '\n'
                + "解析条件：" + parsedDesc + '\n'
                + "候选商品（含真实评价）：\n" + candidateText + '\n'
                + "请只从上述候选 spuId 中挑选并排序。";
    }

    public static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
