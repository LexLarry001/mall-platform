package com.mall.product.shop.dto;

/**
 * 买家端店铺公开信息视图（不含公告与商家账号信息）。
 *
 * @param productCount 该店已上架（status=2）商品数
 */
public record ShopPublicView(String name, String logo, String intro, Integer bizStatus,
                             Long productCount) {
}
