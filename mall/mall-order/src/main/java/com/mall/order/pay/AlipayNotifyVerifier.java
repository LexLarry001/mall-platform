package com.mall.order.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 支付宝异步回调验签器（plan Task 3）。
 *
 * <p>包一层 {@link AlipaySignature#rsaCheckV1} 静态调用，测试 mock 本组件而非静态方法。
 * 密钥取 {@code mall.alipay.alipay-public-key}（公钥模式）；密钥未配置或验签异常一律视为
 * 验签失败——不放行任何未验明身份的报文。</p>
 */
@Component
public class AlipayNotifyVerifier {

    private final String alipayPublicKey;

    public AlipayNotifyVerifier(@Value("${mall.alipay.alipay-public-key:}") String alipayPublicKey) {
        this.alipayPublicKey = alipayPublicKey;
    }

    /** 对异步回调参数验签（RSA2 + utf-8，与 {@link AlipayConfig} 固定约定一致）。 */
    public boolean verify(Map<String, String> params) {
        if (alipayPublicKey == null || alipayPublicKey.isBlank()) {
            return false;
        }
        try {
            return AlipaySignature.rsaCheckV1(params, alipayPublicKey,
                    AlipayConfig.CHARSET, AlipayConfig.SIGN_TYPE);
        } catch (AlipayApiException e) {
            return false;
        }
    }
}
