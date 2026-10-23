package com.mall.admin.dto;

import com.mall.admin.entity.SysRoleEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端角色视图：基础信息 + 已授权的权限 ID 列表。
 */
public record AdminRoleView(Long id,
                            String name,
                            String code,
                            Integer status,
                            LocalDateTime createTime,
                            List<Long> permissionIds) {

    public static AdminRoleView from(SysRoleEntity entity, List<Long> permissionIds) {
        return new AdminRoleView(entity.getId(), entity.getName(), entity.getCode(), entity.getStatus(),
                entity.getCreateTime(), permissionIds == null ? List.of() : permissionIds);
    }
}
