package com.mall.gateway.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参：三端共用，靠接口路径区分账号类型。
 *
 * @param username    用户名
 * @param password    明文密码（服务端 BCrypt 校验）
 * @param captchaKey  验证码标识（取自 {@code GET /api/captcha}）
 * @param captchaCode 用户输入的验证码
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        @NotBlank(message = "验证码标识不能为空") String captchaKey,
        @NotBlank(message = "验证码不能为空") String captchaCode) {
}
