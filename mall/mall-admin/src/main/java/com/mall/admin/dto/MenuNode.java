package com.mall.admin.dto;

import com.mall.admin.entity.SysPermissionEntity;

/**
 * 管理端动态菜单节点。
 *
 * <p>由 {@code PermissionService.listMenus} 按当前管理员的权限码过滤后给出（扁平列表，
 * 交由前端按 {@code parentId} 组装成树）。</p>
 */
public record MenuNode(Long id, Long parentId, String name, String code, String type, String path, Integer sort) {

    /** 由权限表记录映射而来。 */
    public static MenuNode from(SysPermissionEntity entity) {
        return new MenuNode(entity.getId(), entity.getParentId(), entity.getName(), entity.getCode(),
                entity.getType(), entity.getPath(), entity.getSort());
    }
}
