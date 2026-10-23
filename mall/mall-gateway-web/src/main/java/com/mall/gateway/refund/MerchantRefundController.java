package com.mall.gateway.refund;

import com.mall.common.result.R;
import com.mall.order.refund.MerchantRefundService;
import com.mall.order.refund.dto.RefundView;
import com.mall.security.token.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商家退款售后接口（M6 Task 3）。
 * <p>路径前缀 {@code /api/merchant/refund}，SecurityConfig 自动校验 ROLE_MERCHANT。</p>
 */
@RestController
@RequestMapping("/api/merchant/refund")
public class MerchantRefundController {

    private final MerchantRefundService service;

    public MerchantRefundController(MerchantRefundService service) {
        this.service = service;
    }

    /** 本店退款单列表（status 可选：0 待审核 / 2 已退款 / 3 已驳回 / 4 退款失败）。 */
    @GetMapping("/list")
    public R<List<RefundView>> list(@AuthenticationPrincipal LoginUser loginUser,
                                    @RequestParam(required = false) Integer status) {
        return R.ok(service.list(loginUser.shopId(), status));
    }

    /** 退款单详情。 */
    @GetMapping("/{refundNo}")
    public R<RefundView> detail(@AuthenticationPrincipal LoginUser loginUser,
                                @PathVariable String refundNo) {
        return R.ok(service.detail(loginUser.shopId(), refundNo));
    }

    /** 驳回退款（body: remark）。 */
    @PostMapping("/{refundNo}/reject")
    public R<Void> reject(@AuthenticationPrincipal LoginUser loginUser,
                          @PathVariable String refundNo,
                          @RequestBody(required = false) Map<String, String> body) {
        service.reject(loginUser.shopId(), refundNo, body == null ? null : body.get("remark"));
        return R.ok();
    }

    /** 同意退款（同步调支付宝沙箱；body: remark 可选）。 */
    @PostMapping("/{refundNo}/approve")
    public R<Void> approve(@AuthenticationPrincipal LoginUser loginUser,
                           @PathVariable String refundNo,
                           @RequestBody(required = false) Map<String, String> body) {
        service.approve(loginUser.shopId(), refundNo, body == null ? null : body.get("remark"));
        return R.ok();
    }

    /** 退款失败重试（同一 refund_no 重放，支付宝 out_request_no 幂等）。 */
    @PostMapping("/{refundNo}/retry")
    public R<Void> retry(@AuthenticationPrincipal LoginUser loginUser,
                         @PathVariable String refundNo) {
        service.retry(loginUser.shopId(), refundNo);
        return R.ok();
    }
}
