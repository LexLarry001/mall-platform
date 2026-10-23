package com.mall.order.logistics;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.ShipmentEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.ShipmentMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.OrderAggregationService;
import com.mall.order.refund.RefundStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 买家端物流服务：查询轨迹、确认收货（Task 4 实现）。
 */
@Service
public class BuyerLogisticsService {

    private final SubOrderMapper subOrderMapper;
    private final ShipmentMapper shipmentMapper;
    private final OrderMapper orderMapper;
    private final LogisticsProvider provider;
    private final OrderAggregationService aggregationService;

    public BuyerLogisticsService(SubOrderMapper subOrderMapper,
                                  ShipmentMapper shipmentMapper,
                                  OrderMapper orderMapper,
                                  LogisticsProvider provider,
                                  OrderAggregationService aggregationService) {
        this.subOrderMapper = subOrderMapper;
        this.shipmentMapper = shipmentMapper;
        this.orderMapper = orderMapper;
        this.provider = provider;
        this.aggregationService = aggregationService;
    }

    /**
     * 查本人订单的发货单。越权/不存在统一"订单不存在"。
     */
    public ShipmentEntity getShipment(Long userId, String subOrderNo) {
        ShipmentEntity shipment = shipmentMapper.selectOne(Wrappers.<ShipmentEntity>lambdaQuery()
                .eq(ShipmentEntity::getSubOrderNo, subOrderNo));
        if (shipment == null) {
            throw new BizException("订单不存在");
        }
        checkOwnership(userId, shipment);
        return shipment;
    }

    /**
     * 查本人订单的物流轨迹（时间倒序，最新在前）。越权/不存在统一"订单不存在"。
     */
    public List<LogisticsTraceDoc> queryTrace(Long userId, String shipmentNo) {
        ShipmentEntity shipment = shipmentMapper.selectOne(Wrappers.<ShipmentEntity>lambdaQuery()
                .eq(ShipmentEntity::getShipmentNo, shipmentNo));
        if (shipment == null) {
            throw new BizException("订单不存在");
        }
        checkOwnership(userId, shipment);
        return provider.queryTrace(shipmentNo);
    }

    /** 发货单 → 子订单 → 主订单 → 买家，四级归属校验。 */
    private void checkOwnership(Long userId, ShipmentEntity shipment) {
        SubOrderEntity sub = subOrderMapper.selectById(shipment.getSubOrderId());
        if (sub == null) {
            throw new BizException("订单不存在");
        }
        OrderEntity order = orderMapper.selectById(sub.getOrderId());
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BizException("订单不存在");
        }
    }

    /**
     * 买家确认收货：子订单 2→3（待收货 → 已完成）+ 主订单聚合检查。
     *
     * <p>越权/不存在 → "订单不存在"；status≠2 → "当前状态不可确认收货"。</p>
     */
    @Transactional
    public void confirmReceive(Long userId, String orderNo, String subOrderNo) {
        // 权限：子订单必须属于主订单，主订单必须属于该买家
        SubOrderEntity sub = subOrderMapper.selectOne(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getSubOrderNo, subOrderNo));
        if (sub == null) {
            throw new BizException("订单不存在");
        }
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || !order.getId().equals(sub.getOrderId()) || !userId.equals(order.getUserId())) {
            throw new BizException("订单不存在");
        }
        // 7 部分退款：剩余商品仍可确认收货
        if (sub.getStatus() != 2 && sub.getStatus() != RefundStatus.SUB_PART_REFUNDED) {
            throw new BizException("当前状态不可确认收货");
        }
        int rows = subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, 3)
                .eq(SubOrderEntity::getId, sub.getId())
                .in(SubOrderEntity::getStatus, 2, RefundStatus.SUB_PART_REFUNDED));
        if (rows == 0) {
            throw new BizException("当前状态不可确认收货");
        }
        aggregationService.aggregate(order.getId());
    }
}
