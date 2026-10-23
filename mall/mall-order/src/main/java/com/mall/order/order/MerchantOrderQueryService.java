package com.mall.order.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.ShipmentEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.ShipmentMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.dto.MerchantSubOrderView;
import com.mall.order.order.dto.OrderItemView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家订单查询服务（M5 Task 5：商家工作台订单管理）。
 *
 * <p>不变量：一律按登录态 shopId 过滤，商家只能看到自己店铺的子订单。</p>
 */
@Service
public class MerchantOrderQueryService {

    private final SubOrderMapper subOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShipmentMapper shipmentMapper;

    public MerchantOrderQueryService(SubOrderMapper subOrderMapper, OrderMapper orderMapper,
                                     OrderItemMapper orderItemMapper, ShipmentMapper shipmentMapper) {
        this.subOrderMapper = subOrderMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.shipmentMapper = shipmentMapper;
    }

    /**
     * 本店子订单列表：status 可选过滤，按 ID 倒序（新在前）。
     *
     * <p>默认（不传 status）排除 0 待支付：买家未付款时商家无任何可执行动作，
     * 展示出来只会让商家误以为"无法操作"。支付成功（markPaid 把子订单推进到 1）后自动出现。</p>
     */
    public List<MerchantSubOrderView> list(Long shopId, Integer status) {
        List<SubOrderEntity> subs = subOrderMapper.selectList(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getShopId, shopId)
                .ne(status == null, SubOrderEntity::getStatus, 0)
                .eq(status != null, SubOrderEntity::getStatus, status)
                .orderByDesc(SubOrderEntity::getId));
        if (subs.isEmpty()) {
            return List.of();
        }

        Map<Long, OrderEntity> orders = orderMapper.selectBatchIds(
                        subs.stream().map(SubOrderEntity::getOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(OrderEntity::getId, o -> o));

        Map<Long, List<OrderItemEntity>> items = orderItemMapper.selectList(
                        Wrappers.<OrderItemEntity>lambdaQuery()
                                .in(OrderItemEntity::getSubOrderId,
                                        subs.stream().map(SubOrderEntity::getId).toList()))
                .stream().collect(Collectors.groupingBy(OrderItemEntity::getSubOrderId));

        Map<Long, ShipmentEntity> shipments = shipmentMapper.selectList(
                        Wrappers.<ShipmentEntity>lambdaQuery()
                                .in(ShipmentEntity::getSubOrderId,
                                        subs.stream().map(SubOrderEntity::getId).toList()))
                .stream().collect(Collectors.toMap(ShipmentEntity::getSubOrderId, s -> s));

        return subs.stream().map(sub -> {
            OrderEntity order = orders.get(sub.getOrderId());
            ShipmentEntity shipment = shipments.get(sub.getId());
            return new MerchantSubOrderView(
                    sub.getSubOrderNo(),
                    order == null ? null : order.getOrderNo(),
                    sub.getStatus(),
                    sub.getAmount(),
                    sub.getCreateTime(),
                    order == null ? null : order.getReceiver(),
                    order == null ? null : order.getPhone(),
                    order == null ? null : order.getFullAddress(),
                    items.getOrDefault(sub.getId(), List.of()).stream()
                            .map(this::toItemView).toList(),
                    shipment == null ? null : shipment.getShipmentNo(),
                    shipment == null ? null : shipment.getCarrierName(),
                    shipment == null ? null : shipment.getTrackingNo(),
                    shipment == null ? null : shipment.getCurrentStatus(),
                    shipment == null ? null : shipment.getTraceCount());
        }).toList();
    }

    private OrderItemView toItemView(OrderItemEntity entity) {
        return new OrderItemView(entity.getId(), entity.getSpuId(), entity.getSkuId(),
                entity.getProductName(), entity.getMainImg(), entity.getSpecJson(),
                entity.getPrice(), entity.getQuantity(), false);
    }
}
