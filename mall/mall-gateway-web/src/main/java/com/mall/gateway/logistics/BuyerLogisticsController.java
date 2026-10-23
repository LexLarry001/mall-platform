package com.mall.gateway.logistics;

import com.mall.common.result.R;
import com.mall.order.entity.ShipmentEntity;
import com.mall.order.logistics.BuyerLogisticsService;
import com.mall.order.logistics.LogisticsTraceDoc;
import com.mall.security.token.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 买家端物流接口（Task 4 实现）。
 * <p>路径前缀 {@code /api/user/logistics}，SecurityConfig 自动校验 ROLE_USER。</p>
 */
@RestController
@RequestMapping("/api/user/logistics")
public class BuyerLogisticsController {

    private final BuyerLogisticsService service;

    public BuyerLogisticsController(BuyerLogisticsService service) {
        this.service = service;
    }

    /** 查子订单的发货单（仅本人订单）。 */
    @GetMapping("/shipment/{subOrderNo}")
    public R<ShipmentEntity> getShipment(@AuthenticationPrincipal LoginUser loginUser,
                                          @PathVariable String subOrderNo) {
        return R.ok(service.getShipment(loginUser.accountId(), subOrderNo));
    }

    /** 查物流轨迹（时间倒序，最新在前；仅本人订单）。 */
    @GetMapping("/trace/{shipmentNo}")
    public R<List<LogisticsTraceDoc>> queryTrace(@AuthenticationPrincipal LoginUser loginUser,
                                                  @PathVariable String shipmentNo) {
        return R.ok(service.queryTrace(loginUser.accountId(), shipmentNo));
    }

    /** 买家确认收货（子订单 2→3 已完成 + 主订单聚合检查）。 */
    @PostMapping("/confirm")
    public R<Void> confirmReceive(@AuthenticationPrincipal LoginUser loginUser,
                                   @RequestParam String orderNo,
                                   @RequestParam String subOrderNo) {
        service.confirmReceive(loginUser.accountId(), orderNo, subOrderNo);
        return R.ok();
    }
}
