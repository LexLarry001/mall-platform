package com.mall.security.captcha;

import com.mall.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图形验证码单元测试：错误/过期一律业务异常，且验证码一次性使用。
 */
class CaptchaServiceTest {

    private final Map<String, String> redisValues = new HashMap<>();
    private CaptchaService captchaService;

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
        captchaService = new CaptchaService(redis, 5, true);
    }

    @Test
    void create_should_store_code_and_return_key_with_image() {
        CaptchaService.CaptchaResult result = captchaService.create();

        assertThat(result.key()).isNotBlank();
        assertThat(result.imageBase64()).isNotBlank();
        assertThat(redisValues).containsKey("mall:captcha:" + result.key());
    }

    @Test
    void verify_with_wrong_code_should_throw_biz_exception() {
        redisValues.put("mall:captcha:key-1", "abcd");

        assertThatThrownBy(() -> captchaService.verify("key-1", "zzzz"))
                .isInstanceOf(BizException.class)
                .hasMessage("验证码错误或已过期");
    }

    @Test
    void verify_with_missing_or_expired_key_should_throw_biz_exception() {
        assertThatThrownBy(() -> captchaService.verify("not-exist", "abcd"))
                .isInstanceOf(BizException.class)
                .hasMessage("验证码错误或已过期");
    }

    @Test
    void verify_should_be_one_time_use() {
        redisValues.put("mall:captcha:key-2", "abcd");

        captchaService.verify("key-2", "abcd");

        assertThatThrownBy(() -> captchaService.verify("key-2", "abcd"))
                .isInstanceOf(BizException.class)
                .hasMessage("验证码错误或已过期");
    }

    @Test
    void verify_when_disabled_should_pass_through() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CaptchaService disabled = new CaptchaService(redis, 5, false);

        disabled.verify(null, null);
        disabled.verify("any-key", "any-code");
    }
}
