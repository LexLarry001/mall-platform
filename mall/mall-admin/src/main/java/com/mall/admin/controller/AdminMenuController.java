package com.mall.admin.controller;

import com.mall.admin.dto.MenuNode;
import com.mall.admin.service.PermissionService;
import com.mall.common.result.R;
import com.mall.security.token.LoginUser;
import com.mall.security.util.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端动态菜单接口。
 *
 * <p>菜单不写死在前端，而是按"当前管理员自身的权限码"从 RBAC 表裁剪返回；
 * 权限取自登录态（{@link SecurityUtils}），不从请求参数取，防越权。</p>
 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminMenuController {

    private final PermissionService permissionService;

    public AdminMenuController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** 当前管理员的菜单树（扁平节点列表，前端按 parentId 组树）。 */
    @GetMapping("/menus")
    public R<List<MenuNode>> menus() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        return R.ok(permissionService.listMenus(loginUser.permissions()));
    }
}
