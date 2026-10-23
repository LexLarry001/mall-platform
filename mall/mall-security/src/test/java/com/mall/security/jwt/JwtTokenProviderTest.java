package com.mall.security.jwt;

import com.mall.security.token.AccountType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 令牌单元测试：签发/解析往返、过期与篡改必须抛 {@link JwtException}。
 */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-key-please-change-0123456789abcdef";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 12, 7);
    }

    @Test
    void create_then_parse_should_keep_account_claims() {
        String token = provider.createToken(7L, AccountType.MERCHANT, 99L);

        Claims claims = provider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).isEqualTo("MERCHANT");
        assertThat(((Number) claims.get("shopId")).longValue()).isEqualTo(99L);
        assertThat(provider.isRefreshToken(claims)).isFalse();
    }

    @Test
    void refresh_token_should_be_marked_as_refresh() {
        String token = provider.createRefreshToken(3L, AccountType.USER);

        Claims claims = provider.parse(token);

        assertThat(provider.isRefreshToken(claims)).isTrue();
        assertThat(claims.getSubject()).isEqualTo("3");
    }

    @Test
    void parse_expired_token_should_throw_jwt_exception() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("1")
                .claim(JwtTokenProvider.CLAIM_TYPE, AccountType.USER.name())
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> provider.parse(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_tampered_token_should_throw_jwt_exception() {
        String token = provider.createToken(1L, AccountType.USER, null);
        String tampered = token.substring(0, token.length() - 3) + "xyz";

        assertThatThrownBy(() -> provider.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void parse_token_signed_with_another_secret_should_throw_jwt_exception() {
        JwtTokenProvider other = new JwtTokenProvider("another-secret-key-0123456789abcdefghijklmn", 12, 7);
        String foreign = other.createToken(1L, AccountType.USER, null);

        assertThatThrownBy(() -> provider.parse(foreign)).isInstanceOf(JwtException.class);
    }
}
