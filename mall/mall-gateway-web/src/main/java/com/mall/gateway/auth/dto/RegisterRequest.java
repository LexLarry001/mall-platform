package com.mall.gateway.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册入参：买家与商家共用（商家额外填联系电话，管理员不开放注册）。
 *
 * @param username     用户名（全局唯一）
 * @param password     明文密码，服务端 BCrypt 后入库
 * @param contactPhone 联系电话，仅商家注册使用
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        String contactPhone) {
}
