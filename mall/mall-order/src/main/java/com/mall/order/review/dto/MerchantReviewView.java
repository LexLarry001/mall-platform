package com.mall.order.review.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 商家评价管理列表项。 */
public record MerchantReviewView(
        Long id,
        Long spuId,
        String productName,
        String mainImg,
        Integer rating,
        String content,
        List<String> images,
        List<String> tags,
        String userName,
        String merchantReply,
        LocalDateTime replyTime,
        LocalDateTime createTime) {
}
