package com.mall.admin.controller;

import com.mall.admin.dto.AdminRoleSaveRequest;
import com.mall.admin.dto.AdminRoleView;
import com.mall.admin.dto.PermissionView;
import com.mall.admin.service.AdminRoleService;
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
 * 管理端角色管理接口：角色列表/新增/编辑/启停/删除 + 权限授权树。
 *
 * <p>端点除受过滤链的 {@code ROLE_ADMIN} 约束外，还按权限码做方法级鉴权；
 * 角色权限变更后由服务层统一失效相关管理员的权限缓存。</p>
 */
@RestController
@RequestMapping("/api/admin/system/role")
public class AdminRoleController {

    private final AdminRoleService adminRoleService;

    public AdminRoleController(AdminRoleService adminRoleService) {
        this.adminRoleService = adminRoleService;
    }

    /** 角色列表（含已授权权限 ID）。 */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<AdminRoleView>> list() {
        return R.ok(adminRoleService.list());
    }

    /** 权限授权树（扁平节点，前端按 parentId 组树）。 */
    @GetMapping("/permission/list")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<PermissionView>> permissions() {
        return R.ok(adminRoleService.listPermissions());
    }

    /** 新增角色并授权。 */
    @PostMapping
    @PreAuthorize("hasAuthority('system:role:add')")
    public R<Long> create(@RequestBody AdminRoleSaveRequest request) {
        return R.ok(adminRoleService.create(request));
    }

    /** 编辑角色并全量覆盖授权。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:edit')")
    public R<Void> update(@PathVariable Long id, @RequestBody AdminRoleSaveRequest request) {
        adminRoleService.update(id, request);
        return R.ok();
    }

    /** 启用（status=1）/停用（status=0）角色。 */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('system:role:edit')")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminRoleService.changeStatus(id, status);
        return R.ok();
    }

    /** 删除角色（已分配给管理员时拒绝）。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:delete')")
    public R<Void> delete(@PathVariable Long id) {
        adminRoleService.delete(id);
        return R.ok();
    }
}
