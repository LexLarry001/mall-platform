package com.mall.security.jwt;

import com.mall.common.result.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全链集成测试专用桩控制器（仅存在于测试源码）。
 *
 * <p>提供白名单端点与三端角色保护端点，供 {@link SecurityChainTest} 验证放行/401/403 行为。</p>
 */
@RestController
public class SecurityStubController {

    @GetMapping("/api/ping")
    public R<String> ping() {
        return R.ok("pong");
    }

    @GetMapping("/api/user/x")
    public R<String> userEndpoint() {
        return R.ok("user");
    }

    @GetMapping("/api/merchant/x")
    public R<String> merchantEndpoint() {
        return R.ok("merchant");
    }

    @GetMapping("/api/admin/x")
    public R<String> adminEndpoint() {
        return R.ok("admin");
    }
}
