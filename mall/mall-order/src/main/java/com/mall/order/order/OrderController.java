package com.mall.order.order;

import com.mall.common.result.R;
import com.mall.order.order.dto.OrderDetailView;
import com.mall.order.order.dto.OrderSubmitRequest;
import com.mall.order.order.dto.OrderSummaryView;
import com.mall.order.order.dto.SettleView;
import com.mall.security.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家订单接口（ROLE_USER，由过滤链保证）。
 *
 * <p>userId 一律取自登录态（{@link SecurityUtils#getAccountId()}），禁止前端传参。
 * M3 仅结算/下单；列表/详情/取消在 Task 4。</p>
 */
@RestController
@RequestMapping("/api/user/order")
public class OrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    /** 结算页数据：勾选购物车项 + 默认地址 + 金额试算（实时校验库存）。 */
    @GetMapping("/settle")
    public R<SettleView> settle() {
        return R.ok(orderService.settle(SecurityUtils.getAccountId()));
    }

    /** 提交订单：防重 → 拆单 → 扣库存 → 返回主订单号。 */
    @PostMapping
    public R<String> submit(@Valid @RequestBody OrderSubmitRequest request) {
        return R.ok(orderService.submit(SecurityUtils.getAccountId(), request.addressId()));
    }

    /** 我的订单列表（status 可选过滤，按时间倒序）。 */
    @GetMapping("/list")
    public R<List<OrderSummaryView>> list(@RequestParam(required = false) Integer status) {
        return R.ok(orderQueryService.list(SecurityUtils.getAccountId(), status));
    }

    /** 订单详情（主+子+项聚合，仅本人可见）。 */
    @GetMapping("/{orderNo}")
    public R<OrderDetailView> detail(@PathVariable String orderNo) {
        return R.ok(orderQueryService.detail(SecurityUtils.getAccountId(), orderNo));
    }

    /** 取消订单（仅待支付；级联子订单 + 回补库存 + 移除超时锚点）。 */
    @PostMapping("/{orderNo}/cancel")
    public R<Void> cancel(@PathVariable String orderNo) {
        orderService.cancelByUser(SecurityUtils.getAccountId(), orderNo);
        return R.ok();
    }
}
