package com.mall.product.product.dto;

import com.mall.product.entity.ProductEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家商品列表项视图。
 */
public record ProductListItemView(
        Long spuId,
        String name,
        String mainImg,
        BigDecimal price,
        Integer sales,
        Integer status,
        String auditRemark,
        LocalDateTime updateTime) {

    public static ProductListItemView from(ProductEntity entity) {
        return new ProductListItemView(entity.getId(), entity.getName(), entity.getMainImg(),
                entity.getPrice(), entity.getSales(), entity.getStatus(),
                entity.getAuditRemark(), entity.getUpdateTime());
    }
}
