package com.mall.order.review.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.gateway.AiService;
import com.mall.common.exception.BizException;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.review.ai.ReviewPromptBuilder;
import com.mall.order.review.config.ReviewAiProperties;
import com.mall.order.review.dto.RecommendItemView;
import com.mall.order.review.dto.RecommendParsed;
import com.mall.order.review.dto.RecommendRequest;
import com.mall.order.review.dto.RecommendView;
import com.mall.product.buyer.ProductSearchService;
import com.mall.product.buyer.dto.ProductSearchRequest;
import com.mall.product.entity.ProductEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.product.dto.ProductListItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 按评价推荐商品（M10，RAG 客服的检索 + 生成骨架）。
 *
 * <p>流程：规则解析需求 → 商品召回 → 拼装评价证据 → DeepSeek 重排与解释 →
 * 结果校验 → 失败时规则排序。AI 关闭或超限时直接走规则排序。</p>
 */
@Service
public class ReviewRecommendService {

    private static final Logger log = LoggerFactory.getLogger(ReviewRecommendService.class);

    /** 家具意图词 → 类目 ID（与 M10 家具类目树一致）。 */
    private static final Map<String, Long> CATEGORY_WORDS = new LinkedHashMap<>();

    static {
        CATEGORY_WORDS.put("沙发", 102L);
        CATEGORY_WORDS.put("茶几", 103L);
        CATEGORY_WORDS.put("电视柜", 104L);
        CATEGORY_WORDS.put("休闲椅", 105L);
        CATEGORY_WORDS.put("角几", 106L);
        CATEGORY_WORDS.put("双人床", 202L);
        CATEGORY_WORDS.put("床", 202L);
        CATEGORY_WORDS.put("床榻", 203L);
        CATEGORY_WORDS.put("床头柜", 204L);
        CATEGORY_WORDS.put("梳妆台", 205L);
        CATEGORY_WORDS.put("衣柜", 206L);
        CATEGORY_WORDS.put("餐桌", 302L);
        CATEGORY_WORDS.put("餐椅", 303L);
        CATEGORY_WORDS.put("餐边柜", 304L);
        CATEGORY_WORDS.put("吧台椅", 305L);
        CATEGORY_WORDS.put("书桌", 402L);
        CATEGORY_WORDS.put("办公椅", 403L);
        CATEGORY_WORDS.put("书架", 404L);
        CATEGORY_WORDS.put("灯", 502L);
        CATEGORY_WORDS.put("窗帘", 503L);
        CATEGORY_WORDS.put("抱枕", 504L);
        CATEGORY_WORDS.put("装饰画", 505L);
        CATEGORY_WORDS.put("摆件", 506L);
        CATEGORY_WORDS.put("屏风", 602L);
        CATEGORY_WORDS.put("客厅", 101L);
        CATEGORY_WORDS.put("卧室", 201L);
        CATEGORY_WORDS.put("餐厅", 301L);
        CATEGORY_WORDS.put("书房", 401L);
    }

    private static final Pattern BUDGET_PATTERN = Pattern.compile("(\\d{2,6})\\s*(元|块|以内|以下|左右)?");

    private final ProductSearchService searchService;
    private final ProductMapper productMapper;
    private final ReviewQueryService queryService;
    private final AiService aiService;
    private final ReviewAiProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ReviewRecommendService(ProductSearchService searchService,
                                  ProductMapper productMapper,
                                  ReviewQueryService queryService,
                                  AiService aiService,
                                  ReviewAiProperties properties,
                                  StringRedisTemplate redis,
                                  ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.productMapper = productMapper;
        this.queryService = queryService;
        this.aiService = aiService;
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public RecommendView recommend(Long userId, RecommendRequest request) {
        String query = request.query().trim();
        RecommendParsed parsed = parse(query, request);
        String cacheKey = "mall:review:recommend:" + DigestUtils.md5DigestAsHex(
                (query + "|" + parsed.budgetMax() + "|" + parsed.categoryId() + "|" + request.limitOrDefault())
                        .getBytes(StandardCharsets.UTF_8));
        RecommendView cached = readCache(cacheKey);
        if (cached != null) return cached;

        List<Candidate> candidates = recall(parsed, query, request.limitOrDefault());
        if (candidates.isEmpty()) {
            RecommendView empty = new RecommendView(!aiService.isAiEnabled() ? "rule" : "ai", parsed,
                    "暂时没有找到符合条件的商品，可以换个关键词或放宽预算试试。", List.of());
            writeCache(cacheKey, empty);
            return empty;
        }

        RecommendView view;
        if (!aiService.isAiEnabled() || quotaExceeded(userId)) {
            view = ruleRecommend(parsed, candidates, request.limitOrDefault());
        } else {
            view = aiRecommend(query, parsed, candidates, request.limitOrDefault());
        }
        writeCache(cacheKey, view);
        return view;
    }

    // ------------------------------------------------------------------
    // 需求解析与召回
    // ------------------------------------------------------------------

    private RecommendParsed parse(String query, RecommendRequest request) {
        Long categoryId = request.categoryId();
        List<String> keywords = new ArrayList<>();
        if (categoryId == null) {
            for (Map.Entry<String, Long> entry : CATEGORY_WORDS.entrySet()) {
                if (query.contains(entry.getKey())) {
                    categoryId = entry.getValue();
                    keywords.add(entry.getKey());
                    break;
                }
            }
        }
        BigDecimal budgetMax = request.budgetMax();
        if (budgetMax == null) {
            Matcher matcher = BUDGET_PATTERN.matcher(query);
            while (matcher.find()) {
                try {
                    int value = Integer.parseInt(matcher.group(1));
                    if (value >= 50 && value <= 100000) {
                        budgetMax = BigDecimal.valueOf(value);
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略非法数字
                }
            }
        }
        return new RecommendParsed(categoryId, budgetMax, keywords);
    }

    private List<Candidate> recall(RecommendParsed parsed, String query, int limit) {
        Map<Long, ProductEntity> products = new LinkedHashMap<>();
        collect(products, parsed.categoryId() != null ? null : query, parsed.categoryId(), limit * 4);
        if (products.isEmpty() && parsed.categoryId() != null) {
            collect(products, null, parsed.categoryId(), limit * 4);
        }
        if (products.isEmpty()) {
            collect(products, null, null, limit * 8);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ProductEntity product : products.values()) {
            if (parsed.budgetMax() != null && product.getPrice() != null
                    && product.getPrice().compareTo(parsed.budgetMax()) > 0) {
                continue;
            }
            candidates.add(new Candidate(product, queryService.representative(product.getId(),
                    properties.getReviewsPerProduct())));
            if (candidates.size() >= properties.getRecommendCandidates()) break;
        }
        return candidates;
    }

    private void collect(Map<Long, ProductEntity> target, String keyword, Long categoryId, int size) {
        try {
            Page<ProductListItemView> page = searchService.search(
                    new ProductSearchRequest(keyword, categoryId, null, "sales", 1, size));
            for (ProductListItemView item : page.getRecords()) {
                if (target.containsKey(item.spuId())) continue;
                ProductEntity entity = productMapper.selectById(item.spuId());
                if (entity != null) {
                    target.put(entity.getId(), entity);
                }
            }
        } catch (Exception e) {
            log.warn("[review] recall failed, keyword={}, categoryId={}", keyword, categoryId, e);
        }
    }

    // ------------------------------------------------------------------
    // AI 重排
    // ------------------------------------------------------------------

    private RecommendView aiRecommend(String query, RecommendParsed parsed,
                                      List<Candidate> candidates, int limit) {
        Map<Long, String> reviewText = new HashMap<>();
        StringBuilder card = new StringBuilder();
        for (Candidate candidate : candidates) {
            ProductEntity product = candidate.product();
            List<String> tags = queryService.topTags(product.getId(), 4);
            card.append("\n[spuId=").append(product.getId()).append("] ")
                    .append(product.getName())
                    .append(" | 价格 ").append(product.getPrice())
                    .append(" | 评分 ").append(product.getAvgRating())
                    .append(" | 评价 ").append(product.getCommentCount()).append(" 条")
                    .append(tags.isEmpty() ? "" : " | 标签：" + String.join("、", tags))
                    .append('\n');
            StringBuilder text = new StringBuilder();
            for (ProductReviewEntity review : candidate.reviews()) {
                String content = ReviewPromptBuilder.truncate(review.getContent(), 160);
                card.append("  评价：").append(review.getRating()).append("星 ").append(content).append('\n');
                text.append(review.getContent()).append('\n');
            }
            reviewText.put(product.getId(), text.toString());
        }
        String parsedDesc = "类目=" + (parsed.categoryId() == null ? "不限" : parsed.categoryId())
                + "，预算上限=" + (parsed.budgetMax() == null ? "不限" : parsed.budgetMax());
        try {
            Optional<ChatResult> result = aiService.chat(new ChatRequest(
                    ReviewPromptBuilder.RECOMMEND_SYSTEM,
                    ReviewPromptBuilder.recommendUser(query, parsedDesc, card.toString()),
                    0.2, 1000));
            if (result.isEmpty() || result.get().content() == null) {
                return ruleRecommend(parsed, candidates, limit);
            }
            RecommendPayload payload = objectMapper.readValue(
                    ReviewAiSummaryService.extractJson(result.get().content()), RecommendPayload.class);
            if (payload.items() == null || payload.items().isEmpty()) {
                return ruleRecommend(parsed, candidates, limit);
            }
            Map<Long, Candidate> candidateMap = new HashMap<>();
            candidates.forEach(c -> candidateMap.put(c.product().getId(), c));

            Set<Long> seen = new LinkedHashSet<>();
            List<RecommendItemView> items = new ArrayList<>();
            for (RecommendItemPayload item : payload.items()) {
                if (item.spuId() == null || !candidateMap.containsKey(item.spuId())) continue;
                if (!seen.add(item.spuId())) continue;
                Candidate candidate = candidateMap.get(item.spuId());
                ProductEntity product = candidate.product();
                List<String> evidence = validateEvidence(item.evidence(), reviewText.get(product.getId()));
                items.add(new RecommendItemView(product.getId(), product.getName(), product.getMainImg(),
                        product.getPrice(), product.getAvgRating(), product.getCommentCount(),
                        BigDecimal.valueOf(clamp(item.score())).setScale(2, java.math.RoundingMode.HALF_UP),
                        ReviewPromptBuilder.truncate(item.reason(), 80), evidence));
                if (items.size() >= limit) break;
            }
            if (items.isEmpty()) {
                return ruleRecommend(parsed, candidates, limit);
            }
            String summary = payload.summary() == null || payload.summary().isBlank()
                    ? "根据真实评价，为您挑选了以下商品。"
                    : ReviewPromptBuilder.truncate(payload.summary(), 100);
            return new RecommendView("ai", parsed, summary, items);
        } catch (Exception e) {
            log.warn("[review] AI recommend failed, fallback to rule", e);
            return ruleRecommend(parsed, candidates, limit);
        }
    }

    /** 只保留确实出现在对应商品评价原文中的片段。 */
    private static List<String> validateEvidence(List<String> evidence, String allReviewText) {
        if (evidence == null || allReviewText == null) return List.of();
        List<String> valid = new ArrayList<>();
        for (String text : evidence) {
            if (text == null) continue;
            String trimmed = text.trim();
            if (trimmed.length() >= 4 && allReviewText.contains(trimmed)) {
                valid.add(ReviewPromptBuilder.truncate(trimmed, 80));
            }
            if (valid.size() >= 2) break;
        }
        return valid;
    }

    // ------------------------------------------------------------------
    // 规则降级
    // ------------------------------------------------------------------

    private RecommendView ruleRecommend(RecommendParsed parsed, List<Candidate> candidates, int limit) {
        List<RecommendItemView> items = candidates.stream()
                .map(candidate -> {
                    ProductEntity product = candidate.product();
                    double ratingScore = product.getAvgRating() == null ? 0.8
                            : Math.min(product.getAvgRating().doubleValue() / 5.0, 1.0);
                    double countScore = Math.min((product.getCommentCount() == null ? 0 : product.getCommentCount()) / 20.0, 1.0);
                    double score = ratingScore * 0.6 + countScore * 0.25
                            + (parsed.budgetMax() != null ? 0.05 : 0.1);
                    List<String> tags = queryService.topTags(product.getId(), 3);
                    String reason = "评分 " + product.getAvgRating() + "，"
                            + (product.getCommentCount() == null ? 0 : product.getCommentCount()) + " 条真实评价"
                            + (tags.isEmpty() ? "。" : "，用户常提到" + String.join("、", tags) + "。");
                    List<String> evidence = candidate.reviews().stream()
                            .filter(r -> r.getRating() >= 4)
                            .limit(2)
                            .map(r -> ReviewPromptBuilder.truncate(r.getContent(), 80))
                            .toList();
                    return new RecommendItemView(product.getId(), product.getName(), product.getMainImg(),
                            product.getPrice(), product.getAvgRating(), product.getCommentCount(),
                            BigDecimal.valueOf(Math.min(score, 1.0)).setScale(2, java.math.RoundingMode.HALF_UP),
                            reason, evidence);
                })
                .sorted(Comparator.comparing(RecommendItemView::score).reversed())
                .limit(limit)
                .toList();
        return new RecommendView("rule", parsed, "按评分与真实评价数量为您排序。", items);
    }

    // ------------------------------------------------------------------
    // 缓存与配额
    // ------------------------------------------------------------------

    private RecommendView readCache(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) return null;
        try {
            return objectMapper.readValue(value, RecommendView.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String key, RecommendView view) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(view),
                    Math.max(properties.getRecommendCacheTtlMinutes(), 1), java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[review] cache recommend failed", e);
        }
    }

    private boolean quotaExceeded(Long userId) {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String userKey = "mall:review:ai:user:" + userId + ":" + day;
        String globalKey = "mall:review:ai:daily:" + day;
        Long userCalls = redis.opsForValue().increment(userKey);
        Long globalCalls = redis.opsForValue().increment(globalKey);
        if (userCalls != null && userCalls == 1L) {
            redis.expire(userKey, Duration.ofDays(1));
        }
        if (globalCalls != null && globalCalls == 1L) {
            redis.expire(globalKey, Duration.ofDays(1));
        }
        return (userCalls != null && userCalls > properties.getUserDailyLimit())
                || (globalCalls != null && globalCalls > properties.getDailyCallLimit());
    }

    private static double clamp(Double score) {
        if (score == null) return 0.5;
        return Math.max(0, Math.min(1, score));
    }

    private record Candidate(ProductEntity product, List<ProductReviewEntity> reviews) {
    }

    private record RecommendPayload(String summary, List<RecommendItemPayload> items) {
    }

    private record RecommendItemPayload(Long spuId, Double score, String reason, List<String> evidence) {
    }
}
