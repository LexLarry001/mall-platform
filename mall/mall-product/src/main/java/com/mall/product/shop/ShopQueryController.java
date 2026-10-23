package com.mall.product.shop;

import com.mall.common.result.R;
import com.mall.product.shop.dto.ShopPublicView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家端店铺公开接口（匿名可访问，由 SecurityConfig 放行 GET /api/shop/*）。
 */
@RestController
@RequestMapping("/api/shop")
public class ShopQueryController {

    private final ShopQueryService shopQueryService;

    public ShopQueryController(ShopQueryService shopQueryService) {
        this.shopQueryService = shopQueryService;
    }

    /** 店铺公开信息 + 已上架商品数。 */
    @GetMapping("/{shopId}")
    public R<ShopPublicView> info(@PathVariable Long shopId) {
        return R.ok(shopQueryService.publicInfo(shopId));
    }
}
