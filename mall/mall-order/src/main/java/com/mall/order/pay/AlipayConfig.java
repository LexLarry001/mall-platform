package com.mall.order.pay;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝客户端装配（plan Task 1）。
 *
 * <p>沙箱/生产切换只改 {@code mall.alipay.gateway} 与密钥配置；format=json、charset=utf-8、
 * signType=RSA2 为固定约定。密钥缺省为空串（环境变量注入，仓库不落真实密钥），
 * 空密钥仅真实网关调用失败，不影响装配与测试。</p>
 */
@Configuration
public class AlipayConfig {

    /** 固定请求数据格式。 */
    public static final String FORMAT = "json";
    /** 固定字符集。 */
    public static final String CHARSET = "utf-8";
    /** 固定签名算法。 */
    public static final String SIGN_TYPE = "RSA2";

    @Bean
    public AlipayClient alipayClient(
            @Value("${mall.alipay.gateway:https://openapi-sandbox.dl.alipaydev.com/gateway.do}") String gateway,
            @Value("${mall.alipay.app-id:}") String appId,
            @Value("${mall.alipay.private-key:}") String privateKey,
            @Value("${mall.alipay.alipay-public-key:}") String alipayPublicKey) {
        return new DefaultAlipayClient(gateway, appId, privateKey, FORMAT, CHARSET, alipayPublicKey, SIGN_TYPE);
    }
}
