package com.mall.order.review.event;

/** 评价创建事件：事务提交后触发 AI 打标/情感分析。 */
public record ReviewCreatedEvent(Long reviewId) {
}
