package com.mall.order.review.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BizException;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.order.review.dto.MerchantReviewView;
import com.mall.security.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 商家评价管理：只允许操作本店评价，回复仅一次。 */
@Service
public class MerchantReviewService {

    private final ProductReviewMapper reviewMapper;
    private final ReviewQueryService queryService;

    public MerchantReviewService(ProductReviewMapper reviewMapper, ReviewQueryService queryService) {
        this.reviewMapper = reviewMapper;
        this.queryService = queryService;
    }

    public Page<MerchantReviewView> list(int pageNum, int pageSize, Integer rating, Boolean replied) {
        Long shopId = SecurityUtils.getRequiredShopId();
        var query = Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getShopId, shopId)
                .eq(ProductReviewEntity::getStatus, 1);
        if (rating != null && rating >= 1 && rating <= 5) {
            query.eq(ProductReviewEntity::getRating, rating);
        }
        if (Boolean.TRUE.equals(replied)) {
            query.isNotNull(ProductReviewEntity::getMerchantReply).ne(ProductReviewEntity::getMerchantReply, "");
        } else if (Boolean.FALSE.equals(replied)) {
            query.and(w -> w.isNull(ProductReviewEntity::getMerchantReply)
                    .or().eq(ProductReviewEntity::getMerchantReply, ""));
        }
        query.orderByDesc(ProductReviewEntity::getCreateTime);

        Page<ProductReviewEntity> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 50));
        Page<ProductReviewEntity> result = reviewMapper.selectPage(page, query);
        Page<MerchantReviewView> viewPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        viewPage.setRecords(result.getRecords().stream().map(e -> new MerchantReviewView(
                e.getId(), e.getSpuId(), e.getProductName(), e.getMainImg(), e.getRating(),
                e.getContent(), queryService.splitImages(e.getImages()), queryService.parseTags(e.getTags()),
                ReviewQueryService.maskName(e.getUserName()), e.getMerchantReply(), e.getReplyTime(),
                e.getCreateTime())).toList());
        return viewPage;
    }

    @Transactional
    public void reply(Long reviewId, String reply) {
        Long shopId = SecurityUtils.getRequiredShopId();
        ProductReviewEntity review = reviewMapper.selectById(reviewId);
        if (review == null || !shopId.equals(review.getShopId())) {
            throw new BizException("评价不存在或不属于本店");
        }
        if (review.getMerchantReply() != null && !review.getMerchantReply().isBlank()) {
            throw new BizException("该评价已回复");
        }
        review.setMerchantReply(reply.trim());
        review.setReplyTime(LocalDateTime.now());
        reviewMapper.updateById(review);
    }
}
