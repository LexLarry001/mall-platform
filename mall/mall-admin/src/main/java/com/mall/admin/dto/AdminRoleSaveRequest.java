package com.mall.admin.dto;

import java.util.List;

/**
 * 管理端新增/编辑角色请求。
 *
 * <p>{@code code} 必须带 {@code ROLE_} 前缀（与 Spring Security 角色判定保持一致）；
 * {@code permissionIds} 为全量覆盖式授权。</p>
 */
public record AdminRoleSaveRequest(String name,
                                   String code,
                                   Integer status,
                                   List<Long> permissionIds) {
}
