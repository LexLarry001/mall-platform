package com.mall.product.product;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.R;
import com.mall.product.product.dto.MerchantProductDetailView;
import com.mall.product.product.dto.ProductListItemView;
import com.mall.product.product.dto.ProductSaveRequest;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家商品管理接口（ROLE_MERCHANT，由过滤链保证）。
 *
 * <p>shopId 一律取自登录态（{@link SecurityUtils#getRequiredShopId()}），禁止前端传参；
 * 入驻审核未通过的商家在此统一拒绝（RF2）。</p>
 */
@RestController
@RequestMapping("/api/merchant/product")
public class MerchantProductController {

    private final MerchantProductService productService;

    public MerchantProductController(MerchantProductService productService) {
        this.productService = productService;
    }

    /** 发布商品（发布后进入待审核）。 */
    @PostMapping
    public R<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return R.ok(productService.create(SecurityUtils.getRequiredShopId(), request));
    }

    /** 编辑商品（任何编辑回到待审核）。 */
    @PutMapping("/{spuId}")
    public R<Void> update(@PathVariable Long spuId, @Valid @RequestBody ProductSaveRequest request) {
        productService.update(SecurityUtils.getRequiredShopId(), spuId, request);
        return R.ok();
    }

    /** 本店商品分页列表。 */
    @GetMapping("/list")
    public R<Page<ProductListItemView>> list(@RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return R.ok(productService.list(SecurityUtils.getRequiredShopId(), status, keyword, page, size));
    }

    /** 本店商品详情（编辑回显，含 Mongo 富文本）。 */
    @GetMapping("/{spuId}")
    public R<MerchantProductDetailView> detail(@PathVariable Long spuId) {
        return R.ok(productService.detail(SecurityUtils.getRequiredShopId(), spuId));
    }

    /** 商家下架。 */
    @PostMapping("/{spuId}/off")
    public R<Void> off(@PathVariable Long spuId) {
        productService.off(SecurityUtils.getRequiredShopId(), spuId);
        return R.ok();
    }

    /** 商家重新上架（平台违规下架的拒绝）。 */
    @PostMapping("/{spuId}/on")
    public R<Void> on(@PathVariable Long spuId) {
        productService.on(SecurityUtils.getRequiredShopId(), spuId);
        return R.ok();
    }

    /** 删除商品（仅草稿/驳回/已下架）。 */
    @DeleteMapping("/{spuId}")
    public R<Void> delete(@PathVariable Long spuId) {
        productService.delete(SecurityUtils.getRequiredShopId(), spuId);
        return R.ok();
    }
}
