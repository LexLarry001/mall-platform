package com.mall.admin.dto;

import com.mall.admin.entity.SysRoleEntity;
import com.mall.admin.entity.SysUserEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端用户列表视图。
 *
 * <p>对外只暴露必要字段，避免把密码哈希等敏感信息带出接口；角色以 ID + 名称同时回显，
 * 前端无需额外请求即可展示。</p>
 */
public record AdminUserView(Long id,
                            String username,
                            Integer status,
                            LocalDateTime createTime,
                            List<Long> roleIds,
                            List<String> roleNames) {

    /** 由管理员账号记录与所属角色映射而来（不含密码）。 */
    public static AdminUserView from(SysUserEntity entity, List<SysRoleEntity> roles) {
        List<SysRoleEntity> safeRoles = roles == null ? List.of() : roles;
        return new AdminUserView(entity.getId(), entity.getUsername(), entity.getStatus(), entity.getCreateTime(),
                safeRoles.stream().map(SysRoleEntity::getId).toList(),
                safeRoles.stream().map(SysRoleEntity::getName).toList());
    }
}
