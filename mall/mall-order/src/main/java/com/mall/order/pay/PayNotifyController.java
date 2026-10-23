package com.mall.order.pay;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付宝异步回调端点（plan Task 3，无鉴权——SecurityConfig 已放行 POST /api/pay/notify）。
 *
 * <p>按支付宝规范应答纯文本 {@code success}/{@code failure}（非 JSON R 结构）：
 * 返 failure 或未 200，支付宝将按 25h 重发策略补发通知。报文真实性由
 * {@link AlipayNotifyVerifier} 验签保证，不依赖登录态。</p>
 */
@RestController
public class PayNotifyController {

    private final PaymentService paymentService;

    public PayNotifyController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 异步回调：验签 + 金额/APPID 校验 + markPaid 幂等推进。 */
    @PostMapping(value = "/api/pay/notify", produces = "text/plain;charset=UTF-8")
    public String notify(@RequestParam Map<String, String> params) {
        return paymentService.handleNotify(params);
    }
}
