package com.mall.product.category;

import com.mall.common.result.R;
import com.mall.product.category.dto.CategoryNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家端类目导航接口（匿名可访问，由 SecurityConfig 放行 GET /api/category/**）。
 */
@RestController
@RequestMapping("/api/category")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 启用类目树（缓存优先，用于首页导航）。 */
    @GetMapping("/tree")
    public R<List<CategoryNode>> tree() {
        return R.ok(categoryService.buyerTree());
    }
}
