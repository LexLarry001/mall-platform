package com.mall.merchant.shop;

import com.mall.common.result.R;
import com.mall.merchant.shop.dto.ShopInfoView;
import com.mall.merchant.shop.dto.ShopUpdateRequest;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家店铺管理接口（ROLE_MERCHANT，由过滤链保证）。
 *
 * <p>shopId 一律取自登录态（{@link SecurityUtils#getRequiredShopId()}），禁止前端传参；
 * 入驻审核未通过（shopId 为空）的商家在此统一拒绝。</p>
 */
@RestController
@RequestMapping("/api/merchant/shop")
public class MerchantShopController {

    private final ShopService shopService;

    public MerchantShopController(ShopService shopService) {
        this.shopService = shopService;
    }

    /** 查询本店信息。 */
    @GetMapping("/info")
    public R<ShopInfoView> info() {
        return R.ok(shopService.info(SecurityUtils.getRequiredShopId()));
    }

    /** 编辑本店信息（名称/Logo/简介/公告）。 */
    @PutMapping("/info")
    public R<Void> updateInfo(@Valid @RequestBody ShopUpdateRequest request) {
        shopService.updateInfo(SecurityUtils.getRequiredShopId(), request);
        return R.ok();
    }

    /** 切换营业状态：1 营业 / 0 休店。 */
    @PostMapping("/biz-status")
    public R<Void> changeBizStatus(@RequestParam Integer status) {
        shopService.changeBizStatus(SecurityUtils.getRequiredShopId(), status);
        return R.ok();
    }
}
