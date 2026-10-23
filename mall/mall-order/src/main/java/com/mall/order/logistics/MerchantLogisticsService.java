package com.mall.order.logistics;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.ShipmentEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.ShipmentMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.refund.RefundStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商家端物流服务：发货、物流推进（Task 2-3 实现）。
 *
 * <p>权限守卫：所有操作必须匹配 {@code shopId}，越权/不存在统一"订单不存在"。
 * 发货守卫：子订单必须 status=1（待发货）；幂等通过 shipment.sub_order_id 唯一键保证。</p>
 */
@Service
public class MerchantLogisticsService {

    private final SubOrderMapper subOrderMapper;
    private final ShipmentMapper shipmentMapper;
    private final OrderMapper orderMapper;
    private final LogisticsProvider provider;
    private final LogisticsProperties properties;

    public MerchantLogisticsService(SubOrderMapper subOrderMapper,
                                    ShipmentMapper shipmentMapper,
                                    OrderMapper orderMapper,
                                    LogisticsProvider provider,
                                    LogisticsProperties properties) {
        this.subOrderMapper = subOrderMapper;
        this.shipmentMapper = shipmentMapper;
        this.orderMapper = orderMapper;
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * 商家发货：子订单 1→2（待收货）+ shipment INSERT + MongoDB 轨迹（SHIPPED，高德模式含 ROUTE）。
     *
     * <p>越权/不存在 → "订单不存在"；status≠1 → "当前状态不可发货"；
     * 幂等 uk_sub_order 冲突 → "该子订单已发货"。</p>
     *
     * @param shopId      登录态 shopId（权限守卫，禁止前端传参）
     * @param subOrderNo  子订单号
     * @param fromAddress 发货地址（amap 模式必填；高德地理编码失败时自动降级 manual）
     */
    @Transactional
    public String createShipmentWithShop(Long shopId, String subOrderNo,
                                          String carrierCode, String carrierName, String trackingNo,
                                          String fromAddress) {
        SubOrderEntity sub = subOrderMapper.selectOne(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getSubOrderNo, subOrderNo));
        if (sub == null || !shopId.equals(sub.getShopId())) {
            throw new BizException("订单不存在");
        }
        // 7 部分退款：剩余商品仍需发货
        if (sub.getStatus() != 1 && sub.getStatus() != RefundStatus.SUB_PART_REFUNDED) {
            throw new BizException("当前状态不可发货");
        }
        return doCreateShipment(sub, carrierCode, carrierName, trackingNo, fromAddress);
    }

    private String doCreateShipment(SubOrderEntity sub, String carrierCode, String carrierName,
                                     String trackingNo, String fromAddress) {
        String origin = normalizeAddress(fromAddress, "发货地址", 255);
        if (origin == null && "amap".equalsIgnoreCase(properties.getProvider())) {
            throw new BizException("请填写发货地址");
        }
        // 子订单 → 主订单取收货地址快照
        OrderEntity order = orderMapper.selectById(sub.getOrderId());
        String destination = order == null ? null : order.getFullAddress();

        // 路线规划只做一次：成功才落坐标 + provider=amap，失败降级 manual（发货不阻断）
        RoutePlan route = provider.planRoute(origin, destination);
        boolean routed = route != null && route.available() && !route.polyline().isEmpty();

        String shipmentNo = "SH" + IdWorker.getId();
        ShipmentEntity entity = new ShipmentEntity();
        entity.setShipmentNo(shipmentNo);
        entity.setSubOrderId(sub.getId());
        entity.setSubOrderNo(sub.getSubOrderNo());
        entity.setProvider(routed ? "amap" : "manual");
        entity.setCarrierCode(carrierCode);
        entity.setCarrierName(carrierName);
        entity.setTrackingNo(trackingNo);
        entity.setFromAddress(origin);
        entity.setToAddress(destination);
        if (routed) {
            entity.setFromLng(toDecimal(route.fromLng()));
            entity.setFromLat(toDecimal(route.fromLat()));
            entity.setToLng(toDecimal(route.toLng()));
            entity.setToLat(toDecimal(route.toLat()));
        }
        entity.setCurrentStatus(LogisticsStatus.SHIPPED);
        // ROUTE + SHIPPED 两个节点；降级时只有 SHIPPED
        entity.setTraceCount(routed ? 2 : 1);
        try {
            shipmentMapper.insert(entity);
        } catch (Exception uk) {
            throw new BizException("该子订单已发货");
        }

        // 子订单 1→2（待发货 → 待收货）；7 部分退款同样推进为 2
        int rows = subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, 2)
                .eq(SubOrderEntity::getId, sub.getId())
                .in(SubOrderEntity::getStatus, 1, RefundStatus.SUB_PART_REFUNDED));
        if (rows == 0) {
            // 并发守卫：另一个商家已经发货了
            throw new BizException("该子订单已发货");
        }

        // MongoDB 轨迹写入（@Transactional 只包裹 MySQL，MongoDB 不在同一事务）
        provider.createTrace(shipmentNo,
                "包裹已交给" + carrierName + "，运单号 " + trackingNo, route);

        return shipmentNo;
    }

    /** 去空白并校验长度；空串视为 null。 */
    private static String normalizeAddress(String address, String label, int maxLength) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.length() > maxLength) {
            throw new BizException(label + "过长");
        }
        return trimmed;
    }

    private static BigDecimal toDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 查本店发货单。越权/不存在统一"订单不存在"。
     */
    public ShipmentEntity getShipment(Long shopId, String subOrderNo) {
        ShipmentEntity shipment = shipmentMapper.selectOne(Wrappers.<ShipmentEntity>lambdaQuery()
                .eq(ShipmentEntity::getSubOrderNo, subOrderNo));
        if (shipment == null) {
            throw new BizException("订单不存在");
        }
        SubOrderEntity sub = subOrderMapper.selectById(shipment.getSubOrderId());
        if (sub == null || !shopId.equals(sub.getShopId())) {
            throw new BizException("订单不存在");
        }
        return shipment;
    }

    /**
     * 查本店物流轨迹（时间倒序，最新在前）。越权/不存在统一"订单不存在"。
     */
    public List<LogisticsTraceDoc> queryTrace(Long shopId, String shipmentNo) {
        ShipmentEntity shipment = shipmentMapper.selectOne(Wrappers.<ShipmentEntity>lambdaQuery()
                .eq(ShipmentEntity::getShipmentNo, shipmentNo));
        if (shipment == null) {
            throw new BizException("订单不存在");
        }
        SubOrderEntity sub = subOrderMapper.selectById(shipment.getSubOrderId());
        if (sub == null || !shopId.equals(sub.getShopId())) {
            throw new BizException("订单不存在");
        }
        return provider.queryTrace(shipmentNo);
    }

    /**
     * 商家推进物流状态（SHIPPED→IN_TRANSIT→OUT_FOR_DELIVERY→DELIVERED）。
     *
     * <p>终态 DELIVERED 禁止再推进；越权（shipment 不属于本店子订单）→ "订单不存在"。</p>
     *
     * @param shopId      商家 shopId
     * @param shipmentNo  发货单号
     * @param desc        人类可读描述，如"包裹到达上海浦东分拣中心"
     */
    @Transactional
    public String pushStatus(Long shopId, String shipmentNo, String desc) {
        ShipmentEntity shipment = shipmentMapper.selectOne(Wrappers.<ShipmentEntity>lambdaQuery()
                .eq(ShipmentEntity::getShipmentNo, shipmentNo));
        if (shipment == null) {
            throw new BizException("订单不存在");
        }
        // 权限：shipment.subOrderId → subOrder.shopId 匹配
        SubOrderEntity sub = subOrderMapper.selectById(shipment.getSubOrderId());
        if (sub == null || !shopId.equals(sub.getShopId())) {
            throw new BizException("订单不存在");
        }
        String next = LogisticsStatus.next(shipment.getCurrentStatus());
        if (next == null) {
            throw new BizException("物流已签收，无法推进");
        }
        // 更新 shipment.current_status + trace_count
        shipmentMapper.update(null, Wrappers.<ShipmentEntity>lambdaUpdate()
                .set(ShipmentEntity::getCurrentStatus, next)
                .setSql("trace_count = trace_count + 1")
                .eq(ShipmentEntity::getId, shipment.getId())
                .eq(ShipmentEntity::getCurrentStatus, shipment.getCurrentStatus()));
        // 写 MongoDB 轨迹节点
        provider.pushStatus(shipmentNo, next, desc);
        return next;
    }
}
