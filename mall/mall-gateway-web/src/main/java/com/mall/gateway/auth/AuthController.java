package com.mall.gateway.auth;

import com.mall.common.result.R;
import com.mall.gateway.auth.dto.LoginRequest;
import com.mall.gateway.auth.dto.LoginResponse;
import com.mall.gateway.auth.dto.RefreshRequest;
import com.mall.gateway.auth.dto.RegisterRequest;
import com.mall.security.token.AccountType;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三端认证接口。
 *
 * <p>登录/注册路径按账号类型区分，由 {@code SecurityConfig} 放行（无需登录即可访问）；
 * {@code /api/auth/refresh} 也需放行（访问令牌过期时才会用到），
 * {@code /api/auth/logout} 则要求携带有效令牌。</p>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/user/auth/register")
    public R<Void> registerUser(@Valid @RequestBody RegisterRequest request) {
        authService.register(AccountType.USER, request);
        return R.ok();
    }

    @PostMapping("/user/auth/login")
    public R<LoginResponse> loginUser(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(AccountType.USER, request));
    }

    @PostMapping("/merchant/auth/register")
    public R<Void> registerMerchant(@Valid @RequestBody RegisterRequest request) {
        authService.register(AccountType.MERCHANT, request);
        return R.ok();
    }

    @PostMapping("/merchant/auth/login")
    public R<LoginResponse> loginMerchant(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(AccountType.MERCHANT, request));
    }

    @PostMapping("/admin/auth/login")
    public R<LoginResponse> loginAdmin(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(AccountType.ADMIN, request));
    }

    @PostMapping("/auth/refresh")
    public R<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/auth/logout")
    public R<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(resolveToken(authorization));
        return R.ok();
    }

    private static String resolveToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
