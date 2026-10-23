package com.mall.admin.category;

import com.mall.admin.category.dto.CategorySaveRequest;
import com.mall.common.result.R;
import com.mall.product.category.CategoryService;
import com.mall.product.category.dto.CategoryNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台类目管理接口：树查询 + 新增/编辑/状态变更/删除。
 *
 * <p>端点除过滤链的 {@code ROLE_ADMIN} 约束外，按权限码做方法级鉴权；
 * 所有写操作在 {@link CategoryService} 内统一失效买家树缓存。</p>
 */
@RestController
@RequestMapping("/api/admin/category")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 全量类目树（含停用），供管理端维护界面使用。 */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('product:category:list')")
    public R<List<CategoryNode>> tree() {
        return R.ok(categoryService.adminTree());
    }

    /** 新增类目。 */
    @PostMapping
    @PreAuthorize("hasAuthority('product:category:add')")
    public R<Long> create(@RequestBody CategorySaveRequest request) {
        return R.ok(categoryService.create(request.parentId(), request.name(), request.sort()));
    }

    /** 编辑类目（仅名称与排序）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('product:category:edit')")
    public R<Void> update(@PathVariable Long id, @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request.name(), request.sort());
        return R.ok();
    }

    /** 启用（status=1）/停用（status=0）。 */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('product:category:edit')")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        categoryService.changeStatus(id, status);
        return R.ok();
    }

    /** 删除类目（有子类目或商品引用时拒绝，见 plan RF4）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('product:category:delete')")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok();
    }
}
