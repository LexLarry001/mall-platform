package com.mall.security.token;

import java.io.Serializable;
import java.util.Set;

/**
 * 登录态载体，以 JSON 字符串存于 Redis {@code mall:login:{token}}。
 *
 * @param accountId   账号主键
 * @param type        账号类型
 * @param shopId      店铺 ID，仅商家非空
 * @param permissions 权限标识集合，仅管理员非空
 */
public record LoginUser(Long accountId, AccountType type, Long shopId, Set<String> permissions)
        implements Serializable {
}
