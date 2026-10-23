package com.mall.order.pay;

import com.mall.common.result.R;
import com.mall.security.util.SecurityUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家支付接口（ROLE_USER，由过滤链保证）。
 *
 * <p>userId 一律取自登录态（{@link SecurityUtils#getAccountId()}），禁止前端传参。
 * 异步回调端点 {@code /api/pay/notify} 在 Task 3，单独无鉴权放行。</p>
 */
@RestController
@RequestMapping("/api/user/pay")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 发起支付：返回支付宝电脑网站支付表单 HTML。
     * 前端注入隐藏 div 后手动 form.submit() 跳转沙箱收银台（innerHTML 不执行内联 script）。
     */
    @PostMapping("/{orderNo}")
    public R<String> createPayForm(@PathVariable String orderNo) {
        return R.ok(paymentService.createPayForm(SecurityUtils.getAccountId(), orderNo));
    }

    /**
     * 主动查询支付结果（本地无公网收不到异步回调时的兜底推进，plan Task 3）。
     * data=订单当前 status（0 待支付/1 已支付）。
     */
    @PostMapping("/{orderNo}/query")
    public R<Integer> queryPayResult(@PathVariable String orderNo) {
        return R.ok(paymentService.queryPayResult(SecurityUtils.getAccountId(), orderNo));
    }
}
