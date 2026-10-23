package com.mall.product.buyer;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.R;
import com.mall.product.buyer.dto.ProductDetailVO;
import com.mall.product.buyer.dto.ProductSearchRequest;
import com.mall.product.product.dto.ProductListItemView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家端商品接口（匿名可访问，由过滤链 {@code GET /api/product/**} 放行保证）。
 */
@RestController
@RequestMapping("/api/product")
public class BuyerProductController {

    private final ProductSearchService searchService;

    public BuyerProductController(ProductSearchService searchService) {
        this.searchService = searchService;
    }

    /** 搜索已上架商品（关键词/类目/店铺过滤 + 排序 + 分页）。 */
    @GetMapping("/search")
    public R<Page<ProductListItemView>> search(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long categoryId,
                                               @RequestParam(required = false) Long shopId,
                                               @RequestParam(defaultValue = "default") String sort,
                                               @RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size) {
        return R.ok(searchService.search(
                new ProductSearchRequest(keyword, categoryId, shopId, sort, page, size)));
    }

    /** 热搜词 Top10。 */
    @GetMapping("/hot-search")
    public R<List<String>> hotSearch() {
        return R.ok(searchService.hotSearch());
    }

    /** 商品详情聚合（仅已上架可见）。 */
    @GetMapping("/{spuId}")
    public R<ProductDetailVO> detail(@PathVariable Long spuId) {
        return R.ok(searchService.detail(spuId));
    }
}
