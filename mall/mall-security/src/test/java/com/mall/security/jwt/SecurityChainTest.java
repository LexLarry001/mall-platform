package com.mall.security.jwt;

import com.mall.security.SecurityTestApplication;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import com.mall.security.token.TokenStore;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全过滤链集成测试（全量上下文 + MockMvc，TokenStore 以 mock 替换，无需真实 Redis）。
 *
 * <p>钉死行为（对应 plan Review Focus 1/2 与顶号）：</p>
 * <ul>
 *   <li>无/过期/伪造/被顶号 token → HTTP 401 且响应体 {@code code=401}</li>
 *   <li>跨端越权（买家调商家接口、商家调平台接口）→ HTTP 403 且 {@code code=403}</li>
 *   <li>合法商家 token 访问商家接口 → 200</li>
 * </ul>
 */
@SpringBootTest(classes = SecurityTestApplication.class, properties = {
        "mall.jwt.secret=" + SecurityChainTest.SECRET,
        "mall.jwt.access-expire-hours=12",
        "mall.jwt.refresh-expire-days=7"
})
@AutoConfigureMockMvc
class SecurityChainTest {

    static final String SECRET = "security-chain-test-secret-0123456789abcdefghij";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TokenStore tokenStore;

    /** 无需 token 的白名单接口（回归：避免为了鉴权把放行清单弄丢）。 */
    @Test
    void whitelisted_ping_should_be_reachable_without_token() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** Review Focus 1：无 token 访问受保护端点。 */
    @Test
    void no_token_should_return_401_in_R_format() throws Exception {
        mockMvc.perform(get("/api/merchant/x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** Review Focus 1：伪造（不可解析）token。 */
    @Test
    void forged_token_should_return_401() throws Exception {
        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** Review Focus 1：过期 token。 */
    @Test
    void expired_token_should_return_401() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("1")
                .claim(JwtTokenProvider.CLAIM_TYPE, AccountType.MERCHANT.name())
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(key)
                .compact();

        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** 顶号：签名合法但已不是该账号当前有效 token（Redis 指针已指向新 token）。 */
    @Test
    void superseded_token_should_return_401() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.MERCHANT, 99L);
        when(tokenStore.get(token)).thenReturn(merchantUser());
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, 1L)).thenReturn(false);

        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** Review Focus 1（健壮性）：查登录态时 Redis 故障，也应"失败即 401"而不是 500。 */
    @Test
    void redis_failure_during_token_lookup_should_return_401() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.MERCHANT, 99L);
        when(tokenStore.get(token)).thenThrow(new RedisConnectionFailureException("redis down"));

        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** 登出/过期后 Redis 中已无登录态 → 401。 */
    @Test
    void token_without_login_state_should_return_401() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.MERCHANT, 99L);
        when(tokenStore.get(token)).thenReturn(null);

        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** Review Focus 2：买家 token 调商家接口 → 403。 */
    @Test
    void user_token_calling_merchant_api_should_return_403() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.USER, null);
        LoginUser user = new LoginUser(1L, AccountType.USER, null, null);
        when(tokenStore.get(token)).thenReturn(user);
        when(tokenStore.isCurrent(token, AccountType.USER, 1L)).thenReturn(true);

        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** Review Focus 2：商家 token 调平台接口 → 403。 */
    @Test
    void merchant_token_calling_admin_api_should_return_403() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.MERCHANT, 99L);
        when(tokenStore.get(token)).thenReturn(merchantUser());
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, 1L)).thenReturn(true);

        mockMvc.perform(get("/api/admin/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 合法商家 token 访问商家接口 → 200（证明过滤器成功建立认证上下文）。 */
    @Test
    void merchant_token_calling_merchant_api_should_return_200() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.MERCHANT, 99L);
        when(tokenStore.get(token)).thenReturn(merchantUser());
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, 1L)).thenReturn(true);

        mockMvc.perform(get("/api/merchant/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("merchant"));
    }

    /** 合法买家 token 访问买家接口 → 200。 */
    @Test
    void user_token_calling_user_api_should_return_200() throws Exception {
        String token = jwtTokenProvider.createToken(1L, AccountType.USER, null);
        LoginUser user = new LoginUser(1L, AccountType.USER, null, null);
        when(tokenStore.get(token)).thenReturn(user);
        when(tokenStore.isCurrent(token, AccountType.USER, 1L)).thenReturn(true);

        mockMvc.perform(get("/api/user/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("user"));
    }

    private static LoginUser merchantUser() {
        return new LoginUser(1L, AccountType.MERCHANT, 99L, null);
    }
}
