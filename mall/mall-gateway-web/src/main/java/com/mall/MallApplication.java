package com.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 * <p>scanBasePackages 覆盖 com.mall，使各域模块（user / merchant / admin / security ...）的
 * Bean 均被纳入同一 Spring 容器。MongoDB Repository 扫描由 Spring Boot 自动配置激活
 * （classpath 存在 spring-boot-starter-data-mongodb 时自动启用）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.mall")
@org.springframework.scheduling.annotation.EnableScheduling
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
