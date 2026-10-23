package com.mall.security.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 登录态存储：以"账号 → 当前有效令牌"的单值指针实现可踢下线的单会话模型。
 *
 * <ul>
 *   <li>{@code mall:login:{token}} → LoginUser（JSON），以 token 反查登录态</li>
 *   <li>{@code mall:token:{type}:{accountId}} → 当前有效 token（单值）</li>
 *   <li>{@code mall:refresh:{type}:{accountId}} → 当前有效刷新令牌（单值）</li>
 * </ul>
 *
 * <p>{@code mall:token:*} / {@code mall:refresh:*} 均为单值覆盖，因此同一账号二次登录会立刻顶掉旧
 * token（{@link #isCurrent} 比对失败），实现"单会话可踢下线"；退出与强制下线会同时清掉刷新令牌，
 * 避免"退出后仍能靠 refresh 换回新登录态"。</p>
 */
@Component
public class TokenStore {

    private static final String LOGIN_KEY_PREFIX = "mall:login:";
    private static final String ACCOUNT_TOKEN_KEY_PREFIX = "mall:token:";
    private static final String REFRESH_KEY_PREFIX = "mall:refresh:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 写入登录态，并把账号当前有效 token 覆盖为新 token。 */
    public void save(String token, LoginUser user, Duration ttl) {
        redis.opsForValue().set(loginKey(token), toJson(user), ttl);
        redis.opsForValue().set(accountTokenKey(user.type(), user.accountId()), token, ttl);
    }

    /** 按 token 取登录态，不存在返回 {@code null}。 */
    public LoginUser get(String token) {
        String json = redis.opsForValue().get(loginKey(token));
        return json == null ? null : fromJson(json);
    }

    /** 该 token 是否仍是该账号的当前有效 token（顶号失效判定）。 */
    public boolean isCurrent(String token, AccountType type, Long accountId) {
        if (token == null) {
            return false;
        }
        return token.equals(redis.opsForValue().get(accountTokenKey(type, accountId)));
    }

    /** 退出登录：清除 token、账号指针与刷新令牌。 */
    public void remove(String token) {
        LoginUser user = get(token);
        redis.delete(loginKey(token));
        if (user != null) {
            redis.delete(accountTokenKey(user.type(), user.accountId()));
            redis.delete(refreshKey(user.type(), user.accountId()));
        }
    }

    /** 强制下线：清除该账号当前 token 指针、登录态与刷新令牌。 */
    public void kick(AccountType type, Long accountId) {
        String accountKey = accountTokenKey(type, accountId);
        String currentToken = redis.opsForValue().get(accountKey);
        if (currentToken != null) {
            redis.delete(loginKey(currentToken));
        }
        redis.delete(accountKey);
        redis.delete(refreshKey(type, accountId));
    }

    /** 写入刷新令牌指针（单值覆盖，旧刷新令牌随即失效）。 */
    public void saveRefresh(String refreshToken, AccountType type, Long accountId, Duration ttl) {
        redis.opsForValue().set(refreshKey(type, accountId), refreshToken, ttl);
    }

    /** 该刷新令牌是否仍是该账号当前有效的刷新令牌。 */
    public boolean isCurrentRefresh(String refreshToken, AccountType type, Long accountId) {
        if (refreshToken == null) {
            return false;
        }
        return refreshToken.equals(redis.opsForValue().get(refreshKey(type, accountId)));
    }

    private static String loginKey(String token) {
        return LOGIN_KEY_PREFIX + token;
    }

    private static String accountTokenKey(AccountType type, Long accountId) {
        return ACCOUNT_TOKEN_KEY_PREFIX + type.name() + ":" + accountId;
    }

    private static String refreshKey(AccountType type, Long accountId) {
        return REFRESH_KEY_PREFIX + type.name() + ":" + accountId;
    }

    private String toJson(LoginUser user) {
        try {
            return objectMapper.writeValueAsString(user);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("登录态序列化失败", e);
        }
    }

    private LoginUser fromJson(String json) {
        try {
            return objectMapper.readValue(json, LoginUser.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("登录态反序列化失败", e);
        }
    }
}
