package com.mall.admin.dto;

import java.util.List;

/**
 * 管理端新增/编辑用户请求。
 *
 * <p>编辑时 {@code password} 留空表示不修改密码；{@code roleIds} 为全量覆盖式绑定。</p>
 */
public record AdminUserSaveRequest(String username,
                                   String password,
                                   Integer status,
                                   List<Long> roleIds) {
}
