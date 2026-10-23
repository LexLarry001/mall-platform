package com.mall.product.embedding;

/** 商品变更领域事件（上架/下架/审核/编辑后发布，事务提交后触发）。 */
public record ProductChangedEvent(Long spuId) {
}
