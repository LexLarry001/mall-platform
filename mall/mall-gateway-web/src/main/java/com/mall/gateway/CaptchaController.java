package com.mall.gateway;

import com.mall.common.result.R;
import com.mall.security.captcha.CaptchaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图形验证码接口：登录前先取验证码，携带 key 与用户输入提交登录。
 */
@RestController
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @GetMapping("/api/captcha")
    public R<CaptchaService.CaptchaResult> captcha() {
        return R.ok(captchaService.create());
    }
}
