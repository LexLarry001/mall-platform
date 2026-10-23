package com.mall.gateway.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌入参。
 *
 * @param refreshToken 登录时下发的刷新令牌
 */
public record RefreshRequest(
        @NotBlank(message = "刷新令牌不能为空") String refreshToken) {
}
