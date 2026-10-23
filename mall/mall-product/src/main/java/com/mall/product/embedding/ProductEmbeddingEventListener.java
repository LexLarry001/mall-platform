package com.mall.product.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 商品变更事件监听器 —— 事务提交后异步触发向量重建。 */
@Component
public class ProductEmbeddingEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingEventListener.class);

    private final ProductEmbeddingService embeddingService;

    public ProductEmbeddingEventListener(ProductEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Async("mallAiExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductChanged(ProductChangedEvent event) {
        log.info("[AI] product changed, scheduling embedding for spuId={}", event.spuId());
        embeddingService.buildOne(event.spuId());
    }
}
