package com.mall.order.review.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 公开评价列表项（匿名评价的 userName 已脱敏）。 */
public record ReviewView(
        Long id,
        String userName,
        Integer rating,
        String content,
        List<String> images,
        List<String> tags,
        String merchantReply,
        LocalDateTime replyTime,
        LocalDateTime createTime) {
}
