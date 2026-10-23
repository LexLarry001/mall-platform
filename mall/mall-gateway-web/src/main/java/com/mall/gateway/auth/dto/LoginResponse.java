package com.mall.gateway.auth.dto;

/**
 * 登录/刷新返回的令牌对。
 *
 * @param accessToken  访问令牌（放入 {@code Authorization: Bearer} 头）
 * @param refreshToken 刷新令牌（用于换取新的访问令牌）
 * @param accountId    账号 ID
 * @param accountType  账号类型：USER / MERCHANT / ADMIN
 * @param shopId       店铺 ID，仅已通过审核的商家非空（M1 阶段统一为 null）
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        Long accountId,
        String accountType,
        Long shopId) {
}
