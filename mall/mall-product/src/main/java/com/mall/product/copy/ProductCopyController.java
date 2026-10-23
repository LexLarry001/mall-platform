package com.mall.product.copy;

import com.mall.common.result.R;
import com.mall.product.copy.dto.ProductCopyRequest;
import com.mall.product.copy.dto.ProductCopyResult;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 AI 商品文案生成接口。
 *
 * <p>路径：{@code POST /api/merchant/product/copy/generate}。</p>
 */
@RestController
@RequestMapping("/api/merchant/product/copy")
public class ProductCopyController {

    private final ProductCopyService copyService;

    public ProductCopyController(ProductCopyService copyService) {
        this.copyService = copyService;
    }

    /**
     * 生成商品文案（标题/副标题/详情/卖点）。
     *
     * <p>AI 不可用时自动走模板降级，响应中 {@code source} 字段标识来源。</p>
     */
    @PostMapping("/generate")
    public R<ProductCopyResult> generate(@Valid @RequestBody ProductCopyRequest request) {
        // 商家身份由过滤链保证；shopId 仅用于日志/审计，不参与文案生成
        Long shopId = SecurityUtils.getRequiredShopId();
        return R.ok(copyService.generate(request));
    }
}
