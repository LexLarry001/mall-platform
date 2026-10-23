package com.mall.order.review.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BizException;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.order.review.dto.AdminReviewView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台评价审核：隐藏 / 恢复，并同步重算商品评分。 */
@Service
public class AdminReviewService {

    private final ProductReviewMapper reviewMapper;
    private final ReviewQueryService queryService;
    private final ReviewAggregateService aggregateService;

    public AdminReviewService(ProductReviewMapper reviewMapper,
                              ReviewQueryService queryService,
                              ReviewAggregateService aggregateService) {
        this.reviewMapper = reviewMapper;
        this.queryService = queryService;
        this.aggregateService = aggregateService;
    }

    public Page<AdminReviewView> list(int pageNum, int pageSize, Long shopId,
                                      Integer rating, Integer status, String keyword) {
        var query = Wrappers.<ProductReviewEntity>lambdaQuery();
        if (shopId != null) {
            query.eq(ProductReviewEntity::getShopId, shopId);
        }
        if (rating != null && rating >= 1 && rating <= 5) {
            query.eq(ProductReviewEntity::getRating, rating);
        }
        if (status != null) {
            query.eq(ProductReviewEntity::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(ProductReviewEntity::getContent, kw)
                    .or().like(ProductReviewEntity::getProductName, kw)
                    .or().like(ProductReviewEntity::getUserName, kw));
        }
        query.orderByDesc(ProductReviewEntity::getCreateTime);

        Page<ProductReviewEntity> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 50));
        Page<ProductReviewEntity> result = reviewMapper.selectPage(page, query);
        Page<AdminReviewView> viewPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        viewPage.setRecords(result.getRecords().stream().map(e -> new AdminReviewView(
                e.getId(), e.getShopId(), e.getShopName(), e.getSpuId(), e.getProductName(), e.getMainImg(),
                ReviewQueryService.maskName(e.getUserName()), e.getRating(), e.getContent(),
                queryService.splitImages(e.getImages()), queryService.parseTags(e.getTags()),
                e.getStatus(), e.getHideReason(), e.getMerchantReply(), e.getCreateTime())).toList());
        return viewPage;
    }

    @Transactional
    public void hide(Long reviewId, String reason) {
        ProductReviewEntity review = requireReview(reviewId);
        review.setStatus(2);
        review.setHideReason(reason.trim());
        reviewMapper.updateById(review);
        aggregateService.refresh(review.getSpuId());
    }

    @Transactional
    public void restore(Long reviewId) {
        ProductReviewEntity review = requireReview(reviewId);
        review.setStatus(1);
        review.setHideReason(null);
        reviewMapper.updateById(review);
        aggregateService.refresh(review.getSpuId());
    }

    private ProductReviewEntity requireReview(Long reviewId) {
        ProductReviewEntity review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BizException("评价不存在");
        }
        return review;
    }
}
