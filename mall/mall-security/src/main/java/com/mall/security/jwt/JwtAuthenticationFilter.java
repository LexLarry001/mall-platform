package com.mall.security.jwt;

import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import com.mall.security.config.SecurityResponseWriter;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import com.mall.security.token.TokenStore;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器：把 {@code Authorization: Bearer <token>} 转成 Security 上下文。
 *
 * <p>认证过程（任一环节失败都按"未登录"处理，返回 401 而非 500）：</p>
 * <ol>
 *   <li>解析 JWT 签名与有效期（过期/篡改/伪造 → 401）</li>
 *   <li>拒绝刷新令牌直接访问业务接口</li>
 *   <li>比对 Redis 中该账号的当前有效 token（顶号/登出/强制下线 → 401）</li>
 *   <li>以 {@link LoginUser} 作为 principal，账号类型映射为 {@code ROLE_*}，
 *       管理员权限码映射为 GrantedAuthority</li>
 * </ol>
 *
 * <p>本类由 {@link com.mall.security.config.SecurityConfig} 以 {@code @Bean} 方式装配，
 * 不声明为 {@code @Component}：{@code @WebMvcTest} 切片会加载 Filter 类型的 Bean，
 * 但不会加载普通组件，若在此标注 {@code @Component} 会让所有 Web 切片上下文缺少依赖而启动失败。</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenStore tokenStore;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, TokenStore tokenStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        LoginUser loginUser = authenticate(token);
        if (loginUser == null) {
            SecurityResponseWriter.write(response, HttpStatus.UNAUTHORIZED.value(), R.fail(ResultCode.UNAUTHORIZED));
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, authoritiesOf(loginUser));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    /** 校验令牌并返回登录态；任何不可信/已失效的情况都返回 {@code null}。 */
    private LoginUser authenticate(String token) {
        try {
            Claims claims = jwtTokenProvider.parse(token);
            if (jwtTokenProvider.isRefreshToken(claims)) {
                return null;
            }
            AccountType type = AccountType.fromName(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class));
            Long accountId = toAccountId(claims.getSubject());
            if (type == null || accountId == null) {
                return null;
            }
            LoginUser loginUser = tokenStore.get(token);
            if (loginUser == null || !tokenStore.isCurrent(token, type, accountId)) {
                return null;
            }
            return loginUser;
        } catch (RuntimeException e) {
            // 令牌不可信（JwtException/IllegalArgumentException）或登录态存储不可用
            // （Redis 连接故障 → RedisConnectionFailureException、反序列化失败 → IllegalStateException）
            // 一律按"未登录"处理，保证失败即 401，不向调用方暴露 500。
            return null;
        }
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static Long toAccountId(String subject) {
        try {
            return subject == null ? null : Long.valueOf(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<GrantedAuthority> authoritiesOf(LoginUser loginUser) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + loginUser.type().name()));
        if (loginUser.permissions() != null) {
            loginUser.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        }
        return authorities;
    }
}
