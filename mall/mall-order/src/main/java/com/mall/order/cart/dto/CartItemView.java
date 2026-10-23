package com.mall.order.cart.dto;

import java.math.BigDecimal;

/**
 * 购物车列表项视图。
 *
 * @param invalid 失效标记：SKU 删除 / SPU 未上架 / 库存为 0 时为 {@code true}，
 *                失效项不参与结算（plan Task 2 语义，仅打标不自动删除）
 */
public record CartItemView(
        Long skuId,
        Long spuId,
        Long shopId,
        String shopName,
        String productName,
        String skuImg,
        String specJson,
        BigDecimal price,
        Integer stock,
        int quantity,
        boolean checked,
        boolean invalid) {
}
