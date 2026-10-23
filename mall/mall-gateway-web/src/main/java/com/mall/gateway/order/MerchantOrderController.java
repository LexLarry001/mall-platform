package com.mall.gateway.order;

import com.mall.common.result.R;
import com.mall.order.order.MerchantOrderQueryService;
import com.mall.order.order.dto.MerchantSubOrderView;
import com.mall.security.token.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家订单接口（ROLE_MERCHANT，由过滤链保证）。
 *
 * <p>shopId 一律取自登录态，禁止前端传参，避免越权查看他店订单。</p>
 */
@RestController
@RequestMapping("/api/merchant/order")
public class MerchantOrderController {

    private final MerchantOrderQueryService queryService;

    public MerchantOrderController(MerchantOrderQueryService queryService) {
        this.queryService = queryService;
    }

    /** 本店订单列表（status 可选过滤）。 */
    @GetMapping("/list")
    public R<List<MerchantSubOrderView>> list(@AuthenticationPrincipal LoginUser loginUser,
                                              @RequestParam(required = false) Integer status) {
        return R.ok(queryService.list(loginUser.shopId(), status));
    }
}
