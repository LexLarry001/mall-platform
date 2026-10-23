package com.mall.order.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.gateway.AiService;
import com.mall.common.exception.BizException;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.review.ai.ReviewPromptBuilder;
import com.mall.order.review.config.ReviewAiProperties;
import com.mall.order.review.dto.ReviewSummaryView;
import com.mall.product.entity.ProductEntity;
import com.mall.product.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * AI 评价摘要：缓存 → DeepSeek → 校验 → 统计降级。
 *
 * <p>缓存 key 带 review_version，评价新增/隐藏后版本号 +1，旧摘要自然不再命中。</p>
 */
@Service
public class ReviewAiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAiSummaryService.class);

    private final ProductMapper productMapper;
    private final ReviewQueryService queryService;
    private final AiService aiService;
    private final ReviewAiProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ReviewAiSummaryService(ProductMapper productMapper,
                                  ReviewQueryService queryService,
                                  AiService aiService,
                                  ReviewAiProperties properties,
                                  StringRedisTemplate redis,
                                  ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.queryService = queryService;
        this.aiService = aiService;
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public ReviewSummaryView summary(Long spuId) {
        ProductEntity product = productMapper.selectById(spuId);
        if (product == null || product.getStatus() == null || product.getStatus() != 2) {
            throw new BizException("商品不存在或已下架");
        }
        int version = product.getReviewVersion() == null ? 0 : product.getReviewVersion();
        String cacheKey = "mall:review:summary:" + spuId + ":v" + version;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, ReviewSummaryView.class);
            } catch (Exception ignored) {
                // 缓存损坏时按未命中处理
            }
        }

        ReviewSummaryView view;
        int count = product.getCommentCount() == null ? 0 : product.getCommentCount();
        if (count < properties.getSummaryMinCount() || !aiService.isAiEnabled()) {
            view = statSummary(product);
        } else {
            view = aiSummary(product);
        }

        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(view),
                    Math.max(properties.getSummaryCacheTtlMinutes(), 1), TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[review] cache summary failed, spuId={}", spuId, e);
        }
        return view;
    }

    private ReviewSummaryView aiSummary(ProductEntity product) {
        List<ProductReviewEntity> reviews = queryService.latestVisible(
                product.getId(), properties.getSummaryMaxReviews());
        String userPrompt = ReviewPromptBuilder.summaryUser(
                product.getName(), product.getAvgRating(),
                product.getCommentCount() == null ? reviews.size() : product.getCommentCount(), reviews);
        try {
            Optional<ChatResult> result = aiService.chat(
                    new ChatRequest(ReviewPromptBuilder.SUMMARY_SYSTEM, userPrompt, 0.2, 700));
            if (result.isEmpty() || result.get().content() == null || result.get().content().isBlank()) {
                return statSummary(product);
            }
            SummaryPayload payload = objectMapper.readValue(extractJson(result.get().content()), SummaryPayload.class);
            if (payload.summary() == null || payload.summary().isBlank()) {
                return statSummary(product);
            }
            List<String> pros = clean(payload.pros(), 4);
            List<String> cons = clean(payload.cons(), 3);
            List<String> tags = clean(payload.tags(), 8);
            if (tags.isEmpty()) {
                tags = queryService.topTags(product.getId(), 6);
            }
            BigDecimal rate = payload.recommendRate() == null
                    ? statRecommendRate(product.getId())
                    : BigDecimal.valueOf(clamp(payload.recommendRate())).setScale(2, RoundingMode.HALF_UP);
            return new ReviewSummaryView(product.getId(), product.getCommentCount(),
                    product.getAvgRating(), rate,
                    ReviewPromptBuilder.truncate(payload.summary(), 200),
                    pros, cons, tags, "ai", LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[review] AI summary failed, spuId={}, fallback to stat", product.getId(), e);
            return statSummary(product);
        }
    }

    private ReviewSummaryView statSummary(ProductEntity product) {
        int count = product.getCommentCount() == null ? 0 : product.getCommentCount();
        List<String> tags = queryService.topTags(product.getId(), 6);
        List<String> cons = queryService.negativeTags(product.getId(), 2);
        String summary;
        if (count == 0) {
            summary = "该商品暂无评价，欢迎购买后分享真实体验。";
        } else {
            summary = "共 " + count + " 条评价，平均 "
                    + (product.getAvgRating() == null ? "5.0" : product.getAvgRating())
                    + " 星，" + Math.round(statRecommendRate(product.getId()).doubleValue() * 100)
                    + "% 的买家给出 4 星及以上好评。"
                    + (tags.isEmpty() ? "" : "用户普遍提到：" + String.join("、", tags) + "。");
        }
        return new ReviewSummaryView(product.getId(), count, product.getAvgRating(),
                statRecommendRate(product.getId()), summary,
                tags.stream().limit(3).toList(), cons, tags, "stat", LocalDateTime.now());
    }

    private BigDecimal statRecommendRate(Long spuId) {
        Map<Integer, Long> stat = queryService.ratingStat(spuId);
        long total = stat.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return BigDecimal.ZERO;
        long good = stat.getOrDefault(5, 0L) + stat.getOrDefault(4, 0L);
        return BigDecimal.valueOf(good).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /** 去掉 Markdown 代码围栏，截取最外层 JSON。 */
    static String extractJson(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                text = text.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static List<String> clean(List<String> values, int max) {
        if (values == null) return new ArrayList<>();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 20) {
                set.add(trimmed);
            }
            if (set.size() >= max) break;
        }
        return new ArrayList<>(set);
    }

    private static double clamp(Double value) {
        if (value == null) return 0;
        return Math.max(0, Math.min(1, value));
    }

    /** DeepSeek 摘要 JSON 结构。 */
    private record SummaryPayload(String summary, List<String> pros, List<String> cons,
                                  List<String> tags, Double recommendRate) {
    }
}
