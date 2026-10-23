package com.mall.security.token;

/**
 * 账号类型：三端账号彼此独立，登录入口与 JWT 声明都以该类型区分。
 */
public enum AccountType {
    /** 买家。 */
    USER,
    /** 入驻商家。 */
    MERCHANT,
    /** 平台管理员。 */
    ADMIN;

    /**
     * 按名称解析账号类型，未知名称返回 {@code null}（用于解析外部传入的 JWT 声明，避免抛异常）。
     */
    public static AccountType fromName(String name) {
        for (AccountType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
