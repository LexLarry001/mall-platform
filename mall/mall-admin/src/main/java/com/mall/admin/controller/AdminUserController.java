package com.mall.admin.controller;

import com.mall.admin.dto.AdminUserSaveRequest;
import com.mall.admin.dto.AdminUserView;
import com.mall.admin.service.AdminUserService;
import com.mall.common.result.R;
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
 * 管理端账号管理接口：列表 + 新增/编辑/启停/删除 + 角色绑定。
 *
 * <p>端点除受过滤链的 {@code ROLE_ADMIN} 约束外，还按权限码做方法级鉴权，
 * 演示"同一角色内按权限码细分"的方法级鉴权。</p>
 */
@RestController
@RequestMapping("/api/admin/system/user")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** 管理员账号列表（不含密码，含角色回显）。 */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<AdminUserView>> list() {
        return R.ok(adminUserService.list());
    }

    /** 新增管理员账号。 */
    @PostMapping
    @PreAuthorize("hasAuthority('system:user:add')")
    public R<Long> create(@RequestBody AdminUserSaveRequest request) {
        return R.ok(adminUserService.create(request));
    }

    /** 编辑管理员账号（用户名不可改，密码留空不修改）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> update(@PathVariable Long id, @RequestBody AdminUserSaveRequest request) {
        adminUserService.update(id, request);
        return R.ok();
    }

    /** 启用（status=1）/停用（status=0）管理员账号。 */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminUserService.changeStatus(id, status);
        return R.ok();
    }

    /** 删除管理员账号（逻辑删除，内置管理员不可删除）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:delete')")
    public R<Void> delete(@PathVariable Long id) {
        adminUserService.delete(id);
        return R.ok();
    }
}
