package com.mall.admin.dto;

import com.mall.admin.entity.SysPermissionEntity;

/**
 * 角色授权树节点（扁平列表，前端按 parentId 组树）。
 */
public record PermissionView(Long id,
                             Long parentId,
                             String name,
                             String code,
                             String type,
                             String path,
                             Integer sort,
                             Integer status) {

    public static PermissionView from(SysPermissionEntity entity) {
        return new PermissionView(entity.getId(), entity.getParentId(), entity.getName(), entity.getCode(),
                entity.getType(), entity.getPath(), entity.getSort(), entity.getStatus());
    }
}
