package com.mall.order.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.gateway.AiService;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.mapper.ProductReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评价打标与情感分析（异步，失败不影响评价提交）。
 *
 * <p>AI 不可用时使用关键词词表兜底，保证标签筛选功能仍然可用。</p>
 */
@Service
public class ReviewTagService {

    private static final Logger log = LoggerFactory.getLogger(ReviewTagService.class);

    private static final Map<String, String> POSITIVE_WORDS = Map.ofEntries(
            Map.entry("舒服", "坐感舒适"),
            Map.entry("舒适", "坐感舒适"),
            Map.entry("支撑", "支撑到位"),
            Map.entry("扎实", "做工扎实"),
            Map.entry("牢固", "做工扎实"),
            Map.entry("实木", "实木质感"),
            Map.entry("安装", "安装省心"),
            Map.entry("包装", "包装完好"),
            Map.entry("好看", "颜值高"),
            Map.entry("颜值", "颜值高"),
            Map.entry("打理", "好打理"),
            Map.entry("性价比", "性价比高"),
            Map.entry("满意", "整体满意"),
            Map.entry("推荐", "愿意推荐"));

    private static final Map<String, String> NEGATIVE_WORDS = Map.ofEntries(
            Map.entry("味", "异味大"),
            Map.entry("臭", "异味大"),
            Map.entry("硬", "偏硬"),
            Map.entry("塌", "偏软"),
            Map.entry("毛刺", "有毛刺"),
            Map.entry("磕碰", "运输磕碰"),
            Map.entry("划痕", "运输磕碰"),
            Map.entry("慢", "发货慢"),
            Map.entry("脏", "不耐脏"),
            Map.entry("贵", "价格偏高"));

    private static final String SYSTEM = """
            你是家具商城评价标签提取助手。只能依据评价原文提取，不得编造。
            输出严格 JSON，不要 Markdown：{"tags":["标签1","标签2"],"sentiment":1}
            标签最多 4 个、每个不超过 6 个字，从以下维度选择：坐感舒适、支撑到位、偏硬、偏软、
            做工扎实、实木质感、有毛刺、异味大、好打理、不耐脏、尺寸合适、适合小户型、
            安装省心、发货快、发货慢、运输磕碰、性价比高、价格偏高、颜值高、整体满意。
            sentiment：1 正面 / 0 中性 / 2 负面。
            """;

    private final ProductReviewMapper reviewMapper;
    private final AiService aiService;
    private final ObjectMapper objectMapper;

    public ReviewTagService(ProductReviewMapper reviewMapper, AiService aiService, ObjectMapper objectMapper) {
        this.reviewMapper = reviewMapper;
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    /** 为单条评价补齐标签与情感，异常只记录日志。 */
    public void analyze(Long reviewId) {
        ProductReviewEntity review = reviewMapper.selectById(reviewId);
        if (review == null) return;
        try {
            TagPayload payload = aiService.isAiEnabled() ? aiAnalyze(review) : null;
            if (payload == null || payload.tags() == null || payload.tags().isEmpty()) {
                payload = fallback(review);
            }
            review.setTags(objectMapper.writeValueAsString(payload.tags()));
            review.setSentiment(payload.sentiment());
            reviewMapper.updateById(review);
        } catch (Exception e) {
            log.warn("[review] analyze tags failed, reviewId={}", reviewId, e);
        }
    }

    private TagPayload aiAnalyze(ProductReviewEntity review) {
        try {
            String user = "评分：" + review.getRating() + "星\n评价内容：" + review.getContent();
            Optional<ChatResult> result = aiService.chat(new ChatRequest(SYSTEM, user, 0.1, 200));
            if (result.isEmpty() || result.get().content() == null) return null;
            TagPayload payload = objectMapper.readValue(
                    ReviewAiSummaryService.extractJson(result.get().content()), TagPayload.class);
            if (payload.tags() == null) return null;
            List<String> tags = payload.tags().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(4)
                    .toList();
            int sentiment = payload.sentiment() == null ? byRating(review.getRating()) : payload.sentiment();
            return new TagPayload(tags, sentiment);
        } catch (Exception e) {
            log.warn("[review] AI tag failed, reviewId={}", review.getId(), e);
            return null;
        }
    }

    private TagPayload fallback(ProductReviewEntity review) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        String content = review.getContent();
        int sentiment = byRating(review.getRating());
        Map<String, String> words = sentiment == 2 ? NEGATIVE_WORDS : POSITIVE_WORDS;
        for (Map.Entry<String, String> entry : words.entrySet()) {
            if (content.contains(entry.getKey()) && tags.size() < 4) {
                tags.add(entry.getValue());
            }
        }
        if (tags.isEmpty()) {
            tags.add(sentiment == 2 ? "有待改进" : "整体满意");
        }
        return new TagPayload(new ArrayList<>(tags), sentiment);
    }

    private static int byRating(Integer rating) {
        if (rating == null) return 0;
        if (rating >= 4) return 1;
        if (rating == 3) return 0;
        return 2;
    }

    private record TagPayload(List<String> tags, Integer sentiment) {
    }
}
