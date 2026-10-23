package com.mall.admin;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 仅测试用的启动配置。
 *
 * <p>{@code mall-admin} 是被聚合的库模块，自身没有启动类；RBAC 的鉴权集成测试需要一个
 * {@code @SpringBootConfiguration} 作为上下文入口，故在测试包内提供。扫描范围显式限定
 * admin/security/common：M2 起 mall-admin 依赖 mall-merchant/mall-product，若扫全 {@code com.mall}
 * 会把需要真实 mapper/Mongo 的 Bean 带进上下文，破坏"不连真实中间件"的约束。</p>
 *
 * <p>排除数据源与 MyBatis-Plus 自动装配：本测试只验证过滤链与方法级鉴权，mapper / PermissionService
 * 均以 {@code @MockBean} 替换，因此无需连接真实 MySQL，保证 {@code mvn package} 不依赖外部数据库。</p>
 */
@SpringBootApplication(
        scanBasePackages = {"com.mall.admin", "com.mall.security", "com.mall.common"},
        exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
public class AdminTestApplication {
}
