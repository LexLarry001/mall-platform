package com.mall.order.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.refund.RefundStatus;
import org.springframework.stereotype.Service;

/**
 * 主订单状态聚合（M5 确认收货 + M6 退款共用）。
 *
 * <p>终态集合：3 已完成 / 4 已取消 / 6 已退款。
 * 仍有 0/1/2/5/7 任一子订单 → 主订单不动；
 * 全部终态：全部为 6 已退款 → 主订单 1→4 已退款，否则 1→2 已完成。</p>
 */
@Service
public class OrderAggregationService {

    private final SubOrderMapper subOrderMapper;
    private final OrderMapper orderMapper;

    public OrderAggregationService(SubOrderMapper subOrderMapper, OrderMapper orderMapper) {
        this.subOrderMapper = subOrderMapper;
        this.orderMapper = orderMapper;
    }

    /** 按子订单状态聚合主订单（仅 1 已支付可推进，幂等）。 */
    public void aggregate(Long orderId) {
        long unfinished = subOrderMapper.selectCount(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getOrderId, orderId)
                .in(SubOrderEntity::getStatus, 0, 1, 2,
                        RefundStatus.SUB_REFUNDING, RefundStatus.SUB_PART_REFUNDED));
        if (unfinished > 0) {
            return;
        }
        long total = subOrderMapper.selectCount(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getOrderId, orderId));
        long refunded = subOrderMapper.selectCount(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getOrderId, orderId)
                .eq(SubOrderEntity::getStatus, RefundStatus.SUB_REFUNDED));
        int target = total > 0 && refunded == total ? RefundStatus.ORDER_REFUNDED : 2;
        orderMapper.update(null, Wrappers.<OrderEntity>lambdaUpdate()
                .set(OrderEntity::getStatus, target)
                .eq(OrderEntity::getId, orderId)
                .eq(OrderEntity::getStatus, 1));
    }
}
