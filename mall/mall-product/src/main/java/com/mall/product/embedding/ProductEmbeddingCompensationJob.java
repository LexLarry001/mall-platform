package com.mall.product.embedding;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.product.entity.ProductEmbeddingEntity;
import com.mall.product.mapper.ProductEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量生成补偿定时任务：每 5 分钟扫一次失败/待生成的记录重试。
 *
 * <p>仅重试 retry_count < 3 的，超过阈值需要管理员人工介入。</p>
 */
@Component
public class ProductEmbeddingCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingCompensationJob.class);

    private final ProductEmbeddingService embeddingService;
    private final ProductEmbeddingMapper embeddingMapper;

    public ProductEmbeddingCompensationJob(ProductEmbeddingService embeddingService,
                                           ProductEmbeddingMapper embeddingMapper) {
        this.embeddingService = embeddingService;
        this.embeddingMapper = embeddingMapper;
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void retryFailed() {
        List<ProductEmbeddingEntity> failed = embeddingMapper.selectList(
                Wrappers.<ProductEmbeddingEntity>lambdaQuery()
                        .eq(ProductEmbeddingEntity::getStatus, ProductEmbeddingEntity.STATUS_FAIL)
                        .lt(ProductEmbeddingEntity::getRetryCount, 3));
        if (failed.isEmpty()) return;
        log.info("[AI] compensation: retry {} failed embeddings", failed.size());
        for (ProductEmbeddingEntity e : failed) {
            try {
                embeddingService.buildOne(e.getSpuId());
            } catch (Exception ex) {
                log.warn("[AI] retry failed for spuId={}: {}", e.getSpuId(), ex.getMessage());
            }
        }
    }
}
