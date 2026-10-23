package com.mall.order.review.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 买家「我的评价」列表项。 */
public record MyReviewView(
        Long id,
        Long spuId,
        String productName,
        String mainImg,
        Integer rating,
        String content,
        List<String> images,
        List<String> tags,
        String merchantReply,
        Integer status,
        LocalDateTime createTime) {
}
