package com.mall.gateway.refund;

import com.mall.common.result.R;
import com.mall.order.refund.RefundService;
import com.mall.order.refund.dto.RefundApplyRequest;
import com.mall.order.refund.dto.RefundView;
import com.mall.security.token.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 买家退款接口（M6 Task 2）。
 * <p>路径前缀 {@code /api/user/refund}，SecurityConfig 自动校验 ROLE_USER。</p>
 */
@RestController
@RequestMapping("/api/user/refund")
public class BuyerRefundController {

    private final RefundService refundService;

    public BuyerRefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 申请退款（订单项粒度）。返回退款单号。 */
    @PostMapping
    public R<String> apply(@AuthenticationPrincipal LoginUser loginUser,
                           @RequestBody RefundApplyRequest request) {
        return R.ok(refundService.apply(loginUser.accountId(), request));
    }

    /** 某主订单下的退款单列表（新在前）。 */
    @GetMapping("/order/{orderNo}")
    public R<List<RefundView>> listByOrder(@AuthenticationPrincipal LoginUser loginUser,
                                           @PathVariable String orderNo) {
        return R.ok(refundService.listByOrder(loginUser.accountId(), orderNo));
    }

    /** 退款单详情。 */
    @GetMapping("/{refundNo}")
    public R<RefundView> detail(@AuthenticationPrincipal LoginUser loginUser,
                                @PathVariable String refundNo) {
        return R.ok(refundService.detail(loginUser.accountId(), refundNo));
    }
}
