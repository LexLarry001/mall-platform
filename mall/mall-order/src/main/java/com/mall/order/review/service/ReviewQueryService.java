package com.mall.order.review.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.order.review.dto.MyReviewView;
import com.mall.order.review.dto.ReviewPageView;
import com.mall.order.review.dto.ReviewView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评价查询：公开列表、星级分布、摘要语料与展示脱敏。 */
@Service
public class ReviewQueryService {

    private static final int STATUS_VISIBLE = 1;

    private final ProductReviewMapper reviewMapper;
    private final ObjectMapper objectMapper;

    public ReviewQueryService(ProductReviewMapper reviewMapper, ObjectMapper objectMapper) {
        this.reviewMapper = reviewMapper;
        this.objectMapper = objectMapper;
    }

    /** 商品公开评价分页（只含展示中评价）。 */
    public ReviewPageView publicPage(Long spuId, int pageNum, int pageSize,
                                     Integer rating, Boolean hasImage, String tag) {
        QueryWrapper<ProductReviewEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("spu_id", spuId).eq("status", STATUS_VISIBLE);
        if (rating != null && rating >= 1 && rating <= 5) {
            wrapper.eq("rating", rating);
        }
        if (Boolean.TRUE.equals(hasImage)) {
            wrapper.ne("images", "");
        }
        if (tag != null && !tag.isBlank()) {
            wrapper.like("tags", tag.trim());
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");

        Page<ProductReviewEntity> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 50));
        Page<ProductReviewEntity> result = reviewMapper.selectPage(page, wrapper);
        List<ReviewView> records = result.getRecords().stream().map(this::toView).toList();
        return new ReviewPageView(records, result.getTotal(), ratingStat(spuId));
    }

    /** 星级分布，key 1~5（无评价的星级补 0）。 */
    public Map<Integer, Long> ratingStat(Long spuId) {
        QueryWrapper<ProductReviewEntity> wrapper = new QueryWrapper<>();
        wrapper.select("rating", "COUNT(*) AS cnt")
                .eq("spu_id", spuId)
                .eq("status", STATUS_VISIBLE)
                .groupBy("rating");
        List<Map<String, Object>> rows = reviewMapper.selectMaps(wrapper);
        Map<Integer, Long> stat = new LinkedHashMap<>();
        for (int i = 5; i >= 1; i--) {
            stat.put(i, 0L);
        }
        for (Map<String, Object> row : rows) {
            Object rating = row.get("rating");
            Object cnt = row.get("cnt");
            if (rating instanceof Number r && cnt instanceof Number c) {
                stat.put(r.intValue(), c.longValue());
            }
        }
        return stat;
    }

    /** 买家自己的评价分页。 */
    public Page<MyReviewView> myPage(Long userId, int pageNum, int pageSize) {
        Page<ProductReviewEntity> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 50));
        Page<ProductReviewEntity> result = reviewMapper.selectPage(page,
                Wrappers.<ProductReviewEntity>lambdaQuery()
                        .eq(ProductReviewEntity::getUserId, userId)
                        .orderByDesc(ProductReviewEntity::getCreateTime));
        Page<MyReviewView> viewPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        viewPage.setRecords(result.getRecords().stream().map(e -> new MyReviewView(
                e.getId(), e.getSpuId(), e.getProductName(), e.getMainImg(), e.getRating(),
                e.getContent(), splitImages(e.getImages()), parseTags(e.getTags()),
                e.getMerchantReply(), e.getStatus(), e.getCreateTime())).toList());
        return viewPage;
    }

    /** 取最新展示中评价，供 AI 摘要使用。 */
    public List<ProductReviewEntity> latestVisible(Long spuId, int limit) {
        return reviewMapper.selectList(Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getSpuId, spuId)
                .eq(ProductReviewEntity::getStatus, STATUS_VISIBLE)
                .orderByDesc(ProductReviewEntity::getCreateTime)
                .last("LIMIT " + Math.max(limit, 1)));
    }

    /** 代表性评价：好评 2 条 + 差评/中评 1 条，供推荐证据使用。 */
    public List<ProductReviewEntity> representative(Long spuId, int limit) {
        int positiveLimit = Math.max(limit - 1, 1);
        List<ProductReviewEntity> positive = reviewMapper.selectList(Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getSpuId, spuId)
                .eq(ProductReviewEntity::getStatus, STATUS_VISIBLE)
                .ge(ProductReviewEntity::getRating, 4)
                .orderByDesc(ProductReviewEntity::getCreateTime)
                .last("LIMIT " + positiveLimit));
        List<ProductReviewEntity> negative = reviewMapper.selectList(Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getSpuId, spuId)
                .eq(ProductReviewEntity::getStatus, STATUS_VISIBLE)
                .le(ProductReviewEntity::getRating, 3)
                .orderByDesc(ProductReviewEntity::getCreateTime)
                .last("LIMIT 1"));
        List<ProductReviewEntity> merged = new ArrayList<>(positive);
        merged.addAll(negative);
        return merged;
    }

    /** 高频标签（统计降级摘要使用）。 */
    public List<String> topTags(Long spuId, int limit) {
        Map<String, Integer> counter = new HashMap<>();
        for (ProductReviewEntity review : reviewMapper.selectList(Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getSpuId, spuId)
                .eq(ProductReviewEntity::getStatus, STATUS_VISIBLE))) {
            for (String tag : parseTags(review.getTags())) {
                counter.merge(tag, 1, Integer::sum);
            }
        }
        return counter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 负面标签（情感=2 的评价中提取）。 */
    public List<String> negativeTags(Long spuId, int limit) {
        Map<String, Integer> counter = new HashMap<>();
        for (ProductReviewEntity review : reviewMapper.selectList(Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getSpuId, spuId)
                .eq(ProductReviewEntity::getStatus, STATUS_VISIBLE)
                .eq(ProductReviewEntity::getSentiment, 2))) {
            for (String tag : parseTags(review.getTags())) {
                counter.merge(tag, 1, Integer::sum);
            }
        }
        return counter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    public ReviewView toView(ProductReviewEntity e) {
        String name = Integer.valueOf(1).equals(e.getAnonymous()) ? "匿名用户" : maskName(e.getUserName());
        return new ReviewView(e.getId(), name, e.getRating(), e.getContent(),
                splitImages(e.getImages()), parseTags(e.getTags()),
                e.getMerchantReply(), e.getReplyTime(), e.getCreateTime());
    }

    public List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        try {
            List<String> parsed = objectMapper.readValue(tags, new TypeReference<>() {
            });
            return parsed.stream().filter(t -> t != null && !t.isBlank()).limit(10).toList();
        } catch (Exception ignored) {
            return Arrays.stream(tags.split("[,，]"))
                    .map(String::trim)
                    .filter(t -> !t.isBlank())
                    .limit(10)
                    .toList();
        }
    }

    public List<String> splitImages(String images) {
        if (images == null || images.isBlank()) return List.of();
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /** 用户名脱敏：张三 → 张*；user01 → u****1；匿名由调用方处理。 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return "匿名用户";
        int len = name.length();
        if (len == 1) return "*";
        if (len == 2) return name.charAt(0) + "*";
        return name.charAt(0) + "*".repeat(len - 2) + name.charAt(len - 1);
    }

    /** 按评分排序后取前 N，供规则推荐。 */
    public List<ProductReviewEntity> topRated(Long spuId, int limit) {
        return latestVisible(spuId, limit).stream()
                .sorted(Comparator.comparing(ProductReviewEntity::getRating).reversed())
                .limit(limit)
                .toList();
    }
}
