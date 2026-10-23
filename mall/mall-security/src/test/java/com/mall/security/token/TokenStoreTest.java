package com.mall.security.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TokenStore 单元测试：登录态双 key 与"顶号失效"。
 *
 * <p>用内存 Map 支撑的 Redis mock，保证 isCurrent 读到的是 save 真正写入的内容。</p>
 */
class TokenStoreTest {

    private final Map<String, String> redisValues = new HashMap<>();
    private TokenStore tokenStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(invocation -> redisValues.get((String) invocation.getArgument(0)));
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(invocation -> redisValues.remove((String) invocation.getArgument(0)) != null);
        tokenStore = new TokenStore(redis);
    }

    private static LoginUser user(long id, AccountType type) {
        return new LoginUser(id, type, null, null);
    }

    @Test
    void save_then_isCurrent_with_same_token_should_be_true() {
        tokenStore.save("token-a", user(1L, AccountType.USER), Duration.ofHours(12));

        assertThat(tokenStore.isCurrent("token-a", AccountType.USER, 1L)).isTrue();
    }

    @Test
    void second_login_should_invalidate_the_old_token() {
        LoginUser u = user(1L, AccountType.USER);
        tokenStore.save("token-a", u, Duration.ofHours(12));
        tokenStore.save("token-b", u, Duration.ofHours(12));

        assertThat(tokenStore.isCurrent("token-a", AccountType.USER, 1L)).isFalse();
        assertThat(tokenStore.isCurrent("token-b", AccountType.USER, 1L)).isTrue();
    }

    @Test
    void kick_should_invalidate_token_and_login_state() {
        tokenStore.save("token-a", user(1L, AccountType.USER), Duration.ofHours(12));

        tokenStore.kick(AccountType.USER, 1L);

        assertThat(tokenStore.isCurrent("token-a", AccountType.USER, 1L)).isFalse();
        assertThat(tokenStore.get("token-a")).isNull();
    }

    @Test
    void get_should_return_saved_login_user() {
        tokenStore.save("token-a", user(7L, AccountType.MERCHANT), Duration.ofHours(12));

        LoginUser loaded = tokenStore.get("token-a");

        assertThat(loaded).isNotNull();
        assertThat(loaded.accountId()).isEqualTo(7L);
        assertThat(loaded.type()).isEqualTo(AccountType.MERCHANT);
    }

    @Test
    void saveRefresh_then_isCurrentRefresh_should_be_true() {
        tokenStore.saveRefresh("refresh-a", AccountType.USER, 1L, Duration.ofDays(7));

        assertThat(tokenStore.isCurrentRefresh("refresh-a", AccountType.USER, 1L)).isTrue();
    }

    @Test
    void second_refresh_should_invalidate_the_previous_one() {
        tokenStore.saveRefresh("refresh-a", AccountType.USER, 1L, Duration.ofDays(7));
        tokenStore.saveRefresh("refresh-b", AccountType.USER, 1L, Duration.ofDays(7));

        assertThat(tokenStore.isCurrentRefresh("refresh-a", AccountType.USER, 1L)).isFalse();
        assertThat(tokenStore.isCurrentRefresh("refresh-b", AccountType.USER, 1L)).isTrue();
    }

    @Test
    void logout_should_also_clear_refresh_token() {
        tokenStore.save("token-a", user(1L, AccountType.USER), Duration.ofHours(12));
        tokenStore.saveRefresh("refresh-a", AccountType.USER, 1L, Duration.ofDays(7));

        tokenStore.remove("token-a");

        assertThat(tokenStore.isCurrentRefresh("refresh-a", AccountType.USER, 1L)).isFalse();
    }

    @Test
    void kick_should_also_clear_refresh_token() {
        tokenStore.save("token-a", user(1L, AccountType.USER), Duration.ofHours(12));
        tokenStore.saveRefresh("refresh-a", AccountType.USER, 1L, Duration.ofDays(7));

        tokenStore.kick(AccountType.USER, 1L);

        assertThat(tokenStore.isCurrentRefresh("refresh-a", AccountType.USER, 1L)).isFalse();
    }
}
