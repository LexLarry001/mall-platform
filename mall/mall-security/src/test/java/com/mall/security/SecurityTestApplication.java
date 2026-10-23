package com.mall.security;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅测试用的启动配置。
 *
 * <p>{@code mall-security} 是被其它模块依赖的库模块，自身没有启动类；
 * 安全过滤链的集成测试需要一个 {@code @SpringBootConfiguration} 作为上下文入口，
 * 故在测试包内以扫描范围 {@code com.mall.security} 提供。</p>
 */
@SpringBootApplication(scanBasePackages = "com.mall.security")
public class SecurityTestApplication {
}
