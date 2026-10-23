package com.mall.order.review.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 平台评价审核列表项。 */
public record AdminReviewView(
        Long id,
        Long shopId,
        String shopName,
        Long spuId,
        String productName,
        String mainImg,
        String userName,
        Integer rating,
        String content,
        List<String> images,
        List<String> tags,
        Integer status,
        String hideReason,
        String merchantReply,
        LocalDateTime createTime) {
}
