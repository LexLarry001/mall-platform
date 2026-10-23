package com.mall.order.review.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.product.entity.ProductEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.product.ProductCacheService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商品评分聚合：评价新增 / 隐藏 / 恢复后按 spuId 重算评分与评价数。
 *
 * <p>采用「重算」而非「累加」，避免并发与隐藏操作导致评分漂移；
 * 同时把 review_version +1，使 AI 摘要缓存自然失效，最后删除商品详情缓存。</p>
 */
@Service
public class ReviewAggregateService {

    private static final int STATUS_VISIBLE = 1;

    private final ProductReviewMapper reviewMapper;
    private final ProductMapper productMapper;
    private final ProductCacheService productCacheService;

    public ReviewAggregateService(ProductReviewMapper reviewMapper,
                                  ProductMapper productMapper,
                                  ProductCacheService productCacheService) {
        this.reviewMapper = reviewMapper;
        this.productMapper = productMapper;
        this.productCacheService = productCacheService;
    }

    /** 重算指定商品的评分、评价数、评价版本号，并失效商品详情缓存。 */
    public void refresh(Long spuId) {
        if (spuId == null) return;
        List<ProductReviewEntity> visible = reviewMapper.selectList(
                Wrappers.<ProductReviewEntity>lambdaQuery()
                        .eq(ProductReviewEntity::getSpuId, spuId)
                        .eq(ProductReviewEntity::getStatus, STATUS_VISIBLE));

        int count = visible.size();
        BigDecimal avg = count == 0
                ? new BigDecimal("5.0")
                : BigDecimal.valueOf(visible.stream().mapToInt(ProductReviewEntity::getRating).sum())
                        .divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);

        productMapper.update(null, Wrappers.<ProductEntity>lambdaUpdate()
                .eq(ProductEntity::getId, spuId)
                .set(ProductEntity::getAvgRating, avg)
                .set(ProductEntity::getCommentCount, count)
                .setSql("review_version = review_version + 1"));

        productCacheService.evict(spuId);
    }
}
