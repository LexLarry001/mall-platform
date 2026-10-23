package com.mall.security.captcha;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.mall.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 图形验证码：生成 4 位线干扰码，答案存 Redis {@code mall:captcha:{key}}（TTL 默认 5min）。
 *
 * <p>校验一次性：无论对错都会消费掉该 key，避免对同一张验证码暴力试错。</p>
 */
@Service
public class CaptchaService {

    private static final String CAPTCHA_KEY_PREFIX = "mall:captcha:";
    private static final String ERROR_MESSAGE = "验证码错误或已过期";
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_COUNT = 4;
    private static final int LINE_COUNT = 20;

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final boolean enabled;

    public CaptchaService(StringRedisTemplate redis,
                          @Value("${mall.captcha.expire-minutes:5}") long expireMinutes,
                          @Value("${mall.captcha.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(expireMinutes);
        this.enabled = enabled;
    }

    /** 生成验证码：返回 key（提交登录时回传）与 PNG base64 图片。 */
    public CaptchaResult create() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(WIDTH, HEIGHT, CODE_COUNT, LINE_COUNT);
        String key = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(CAPTCHA_KEY_PREFIX + key, captcha.getCode(), ttl);
        return new CaptchaResult(key, captcha.getImageBase64());
    }

    /** 校验验证码，失败抛 {@link BizException}（不区分错误与过期，避免枚举）。mall.captcha.enabled=false 时直通（仅限本地 e2e）。 */
    public void verify(String key, String code) {
        if (!enabled) {
            return;
        }
        if (key == null || key.isBlank() || code == null || code.isBlank()) {
            throw new BizException(ERROR_MESSAGE);
        }
        String redisKey = CAPTCHA_KEY_PREFIX + key;
        String stored = redis.opsForValue().get(redisKey);
        redis.delete(redisKey);
        if (stored == null || !stored.equalsIgnoreCase(code)) {
            throw new BizException(ERROR_MESSAGE);
        }
    }

    /**
     * 验证码结果。
     *
     * @param key         验证码 key
     * @param imageBase64 PNG 图片（base64，不含 data URI 前缀）
     */
    public record CaptchaResult(String key, String imageBase64) {
    }
}
