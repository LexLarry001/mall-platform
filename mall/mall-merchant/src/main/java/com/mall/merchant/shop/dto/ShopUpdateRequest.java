package com.mall.merchant.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 商家店铺信息编辑入参。仅允许这四个字段，归属（merchantId）与营业状态不在本接口变更。
 *
 * @param name   店铺名称
 * @param logo   店铺 Logo URL
 * @param intro  店铺简介
 * @param notice 店铺公告
 */
public record ShopUpdateRequest(
        @NotBlank(message = "店铺名称不能为空") @Size(max = 64, message = "店铺名称过长") String name,
        @Size(max = 255, message = "店铺 Logo URL 过长") String logo,
        @Size(max = 500, message = "店铺简介过长") String intro,
        @Size(max = 500, message = "店铺公告过长") String notice) {
}
