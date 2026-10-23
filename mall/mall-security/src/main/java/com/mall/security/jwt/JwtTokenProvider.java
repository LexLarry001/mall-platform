package com.mall.security.jwt;

import com.mall.security.token.AccountType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（HMAC-SHA，算法由密钥长度决定：默认 64 字节密钥 → HS512）。
 *
 * <p>payload 约定：{@code sub=accountId}、{@code type=USER|MERCHANT|ADMIN}、
 * {@code typ=access|refresh}、{@code shopId}（仅商家非空）。</p>
 *
 * <p>令牌只承载"是谁"，不承载权限明细；权限明细以 Redis 中的登录态（{@code LoginUser}）为准，
 * 因此顶号/强制下线能立刻生效。</p>
 */
@Component
public class JwtTokenProvider {

    /** 账号类型 claim。 */
    public static final String CLAIM_TYPE = "type";
    /** 令牌类别 claim（access / refresh）。 */
    public static final String CLAIM_TYP = "typ";
    /** 店铺 ID claim，仅商家令牌非空。 */
    public static final String CLAIM_SHOP_ID = "shopId";

    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtTokenProvider(@Value("${mall.jwt.secret}") String secret,
                            @Value("${mall.jwt.access-expire-hours:12}") long accessExpireHours,
                            @Value("${mall.jwt.refresh-expire-days:7}") long refreshExpireDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofHours(accessExpireHours);
        this.refreshTtl = Duration.ofDays(refreshExpireDays);
    }

    /** 签发访问令牌（默认 12h）。 */
    public String createToken(Long accountId, AccountType type, Long shopId) {
        return build(accountId, type, shopId, accessTtl, TOKEN_TYPE_ACCESS);
    }

    /** 签发刷新令牌（默认 7d），不含 shopId。 */
    public String createRefreshToken(Long accountId, AccountType type) {
        return build(accountId, type, null, refreshTtl, TOKEN_TYPE_REFRESH);
    }

    /**
     * 解析并校验签名与有效期。
     *
     * @throws io.jsonwebtoken.JwtException 令牌过期、签名不匹配或被篡改
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** 是否为刷新令牌（刷新令牌不得用于访问业务接口）。 */
    public boolean isRefreshToken(Claims claims) {
        return TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TYP, String.class));
    }

    private String build(Long accountId, AccountType type, Long shopId, Duration ttl, String tokenType) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim(CLAIM_TYPE, type.name())
                .claim(CLAIM_TYP, tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .id(UUID.randomUUID().toString())
                .signWith(key);
        if (shopId != null) {
            builder.claim(CLAIM_SHOP_ID, shopId);
        }
        return builder.compact();
    }
}
