package com.mall.order.review.event;

import com.mall.order.review.service.ReviewTagService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 评价创建后异步打标；事务提交后才执行，避免回滚产生脏数据。 */
@Component
public class ReviewAiTagListener {

    private final ReviewTagService tagService;

    public ReviewAiTagListener(ReviewTagService tagService) {
        this.tagService = tagService;
    }

    @Async("mallAiExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreatedEvent event) {
        tagService.analyze(event.reviewId());
    }
}
