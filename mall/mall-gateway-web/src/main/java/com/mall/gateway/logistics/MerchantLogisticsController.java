package com.mall.gateway.logistics;

import com.mall.common.result.R;
import com.mall.order.entity.ShipmentEntity;
import com.mall.order.logistics.LogisticsTraceDoc;
import com.mall.order.logistics.MerchantLogisticsService;
import com.mall.security.token.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商家端物流接口（Task 2-3 实现）。
 * <p>路径前缀 {@code /api/merchant/ship}，SecurityConfig 自动校验 ROLE_MERCHANT。</p>
 */
@RestController
@RequestMapping("/api/merchant/ship")
public class MerchantLogisticsController {

    private final MerchantLogisticsService service;

    public MerchantLogisticsController(MerchantLogisticsService service) {
        this.service = service;
    }

    /** 商家发货：填承运商 + 运单号 + 发货地址。子订单 1→2 待收货。 */
    @PostMapping("/{subOrderNo}")
    public R<String> createShipment(@AuthenticationPrincipal LoginUser loginUser,
                                     @PathVariable String subOrderNo,
                                     @RequestBody Map<String, String> body) {
        String shipmentNo = service.createShipmentWithShop(loginUser.shopId(),
                subOrderNo, body.get("carrierCode"), body.get("carrierName"),
                body.get("trackingNo"), body.get("fromAddress"));
        return R.ok(shipmentNo);
    }

    /** 商家手动推进物流状态。body 传 desc（人类可读描述）。 */
    @PostMapping("/progress/{shipmentNo}")
    public R<String> pushStatus(@AuthenticationPrincipal LoginUser loginUser,
                                 @PathVariable String shipmentNo,
                                 @RequestBody Map<String, String> body) {
        String next = service.pushStatus(loginUser.shopId(), shipmentNo, body.get("desc"));
        return R.ok(next);
    }

    /** 查本店发货单。 */
    @GetMapping("/{subOrderNo}")
    public R<ShipmentEntity> getShipment(@AuthenticationPrincipal LoginUser loginUser,
                                          @PathVariable String subOrderNo) {
        return R.ok(service.getShipment(loginUser.shopId(), subOrderNo));
    }

    /** 查本店物流轨迹（时间倒序，最新在前）。 */
    @GetMapping("/trace/{shipmentNo}")
    public R<List<LogisticsTraceDoc>> queryTrace(@AuthenticationPrincipal LoginUser loginUser,
                                                  @PathVariable String shipmentNo) {
        return R.ok(service.queryTrace(loginUser.shopId(), shipmentNo));
    }
}
