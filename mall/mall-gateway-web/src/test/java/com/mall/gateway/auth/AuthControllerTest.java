package com.mall.gateway.auth;

import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.gateway.auth.dto.LoginResponse;
import com.mall.security.token.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口切片测试：只关心入参校验与响应包装。
 *
 * <p>{@code GlobalExceptionHandler} 不在切片默认扫描范围内，需显式 {@code @Import}；
 * 安全过滤链行为由 mall-security 的 SecurityChainTest 覆盖，故此处关闭过滤器。</p>
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void login_without_captcha_should_return_param_error() throws Exception {
        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"secret\",\"captchaKey\":\"k\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void register_without_password_should_return_param_error() throws Exception {
        mockMvc.perform(post("/api/user/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void login_should_return_token_pair() throws Exception {
        when(authService.login(eq(AccountType.USER), any()))
                .thenReturn(new LoginResponse("access-a", "refresh-a", 1L, "USER", null));

        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"secret\","
                                + "\"captchaKey\":\"k\",\"captchaCode\":\"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").value("access-a"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-a"))
                .andExpect(jsonPath("$.data.accountType").value("USER"));
    }
}
