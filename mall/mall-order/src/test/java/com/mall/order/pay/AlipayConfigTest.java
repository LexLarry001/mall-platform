package com.mall.order.pay;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝客户端装配测试（plan Task 1）。
 *
 * <p>钉死语义：AlipayConfig 以 gateway/appId/应用私钥/支付宝公钥装配出 DefaultAlipayClient，
 * format=json、charset=utf-8、signType=RSA2 固定；空密钥也能装配（仅真实调用会失败），
 * 保证开发期无沙箱密钥时上下文与全部测试可正常启动。</p>
 */
class AlipayConfigTest {

    @Test
    void assemblesDefaultAlipayClientWithGivenConfig() {
        AlipayClient client = new AlipayConfig().alipayClient(
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                "2021000122666666", "app-private-key", "alipay-public-key");

        assertThat(client).isNotNull().isInstanceOf(DefaultAlipayClient.class);
    }

    @Test
    void assemblesClientEvenWhenKeysAreEmpty() {
        AlipayClient client = new AlipayConfig().alipayClient(
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do", "", "", "");

        assertThat(client).isNotNull().isInstanceOf(DefaultAlipayClient.class);
    }
}
