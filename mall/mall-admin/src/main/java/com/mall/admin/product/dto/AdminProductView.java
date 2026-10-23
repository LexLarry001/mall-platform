package com.mall.admin.product.dto;

import com.mall.product.entity.ProductEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台商品管理视图（审核列表 / 管控列表共用），附店铺名联查结果。
 */
public record AdminProductView(
        Long id,
        Long shopId,
        String shopName,
        Long categoryId,
        String name,
        String subTitle,
        String mainImg,
        BigDecimal price,
        Integer sales,
        Integer status,
        String auditRemark,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public static AdminProductView from(ProductEntity entity, String shopName) {
        return new AdminProductView(
                entity.getId(),
                entity.getShopId(),
                shopName,
                entity.getCategoryId(),
                entity.getName(),
                entity.getSubTitle(),
                entity.getMainImg(),
                entity.getPrice(),
                entity.getSales(),
                entity.getStatus(),
                entity.getAuditRemark(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
