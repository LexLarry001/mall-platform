package com.mall.security.config;

import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import com.mall.security.jwt.JwtAuthenticationFilter;
import com.mall.security.jwt.JwtTokenProvider;
import com.mall.security.token.AccountType;
import com.mall.security.token.TokenStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 过滤链。
 *
 * <p>自定义的 {@link SecurityFilterChain} 会抑制 Spring Boot 的默认链，因此放行清单与
 * 三端角色规则都以本类为准：</p>
 * <ul>
 *   <li>放行：ping / 验证码 / 三端登录注册 / 令牌刷新 / OpenAPI 文档 / 支付宝异步回调（无 JWT，靠验签防伪）</li>
 *   <li>{@code /api/user/**} 需 {@code ROLE_USER}，{@code /api/merchant/**} 需 {@code ROLE_MERCHANT}，
 *       {@code /api/admin/**} 需 {@code ROLE_ADMIN}（跨端越权在此拦成 403）</li>
 *   <li>其余接口需登录；未登录 401、无权限 403，响应体统一为 {@link R} 结构</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 免认证接口。 */
    private static final String[] PERMIT_ALL = {
            "/api/ping",
            "/api/captcha",
            "/api/auth/refresh",
            "/api/*/auth/login",
            "/api/*/auth/register",
            "/static/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    /**
     * JWT 认证过滤器。
     *
     * <p>此处以 {@code @Bean} 装配而非在过滤器上标注 {@code @Component}：
     * {@code @WebMvcTest} 切片会加载 Filter 类型的 Bean，却不加载普通组件，
     * 若标注 {@code @Component} 会让所有 Web 切片上下文因缺少依赖而启动失败。</p>
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, TokenStore tokenStore) {
        return new JwtAuthenticationFilter(jwtTokenProvider, tokenStore);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                SecurityResponseWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
                                        R.fail(ResultCode.UNAUTHORIZED)))
                        .accessDeniedHandler((request, response, ex) ->
                                SecurityResponseWriter.write(response, HttpStatus.FORBIDDEN.value(),
                                        R.fail(ResultCode.FORBIDDEN))))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers(PERMIT_ALL).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shop/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/category/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/product/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/pay/notify").permitAll()
                        .requestMatchers("/api/user/**").hasRole(AccountType.USER.name())
                        .requestMatchers("/api/merchant/**").hasRole(AccountType.MERCHANT.name())
                        .requestMatchers("/api/admin/**").hasRole(AccountType.ADMIN.name())
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码编码器：全平台统一 BCrypt，任何账号密码都不得以明文或自定义哈希入库。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 阻止 Spring Boot 把 JWT 过滤器再注册成 Servlet 过滤器。
     *
     * <p>过滤器已显式挂在安全链上；若容器再注册一次，同一请求会被处理两遍。</p>
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
