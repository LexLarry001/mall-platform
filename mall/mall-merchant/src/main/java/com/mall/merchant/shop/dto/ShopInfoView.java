package com.mall.merchant.shop.dto;

import com.mall.product.entity.ShopEntity;

/**
 * 商家端店铺信息视图（含公告，商家自管视角）。
 */
public record ShopInfoView(Long id, String name, String logo, String intro, String notice,
                           Integer bizStatus) {

    public static ShopInfoView from(ShopEntity shop) {
        return new ShopInfoView(shop.getId(), shop.getName(), shop.getLogo(), shop.getIntro(),
                shop.getNotice(), shop.getBizStatus());
    }
}
