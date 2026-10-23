package com.mall.order.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.dto.OrderDetailView;
import com.mall.order.order.dto.OrderItemView;
import com.mall.order.order.dto.OrderSummaryView;
import com.mall.order.order.dto.SubOrderView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单查询服务（plan Task 4：列表 + 详情）。
 *
 * <p>不变量：一律按登录态 userId 过滤；详情越权/不存在统一"订单不存在"，不泄露存在性。</p>
 */
@Service
public class OrderQueryService {

    private final OrderMapper orderMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductReviewMapper productReviewMapper;

    public OrderQueryService(OrderMapper orderMapper, SubOrderMapper subOrderMapper,
                             OrderItemMapper orderItemMapper, ProductReviewMapper productReviewMapper) {
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productReviewMapper = productReviewMapper;
    }

    /** 我的订单列表：status 可选过滤，按 ID 倒序（新在前）；订单项跨子订单聚合。 */
    public List<OrderSummaryView> list(Long userId, Integer status) {
        List<OrderEntity> orders = orderMapper.selectList(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getUserId, userId)
                .eq(status != null, OrderEntity::getStatus, status)
                .orderByDesc(OrderEntity::getId));
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, List<OrderItemEntity>> itemsByOrderId = itemsByOrderId(
                orders.stream().map(OrderEntity::getId).toList());
        var reviewedIds = reviewedItemIds(itemsByOrderId.values().stream()
                .flatMap(List::stream).toList());
        return orders.stream()
                .map(order -> new OrderSummaryView(order.getOrderNo(), order.getStatus(),
                        order.getTotalAmount(), order.getPayAmount(),
                        order.getCreateTime(), order.getExpireTime(),
                        itemsByOrderId.getOrDefault(order.getId(), List.of()).stream()
                                .map(item -> toItemView(item, reviewedIds)).toList()))
                .toList();
    }

    /** 订单详情：主+子+项三级聚合；仅本人可见。 */
    public OrderDetailView detail(Long userId, String orderNo) {
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException("订单不存在");
        }
        List<SubOrderEntity> subs = subOrderMapper.selectList(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getOrderId, order.getId())
                .orderByAsc(SubOrderEntity::getId));
        Map<Long, List<OrderItemEntity>> itemsBySubId = itemsBySubId(
                subs.stream().map(SubOrderEntity::getId).toList());
        var reviewedIds = reviewedItemIds(itemsBySubId.values().stream()
                .flatMap(List::stream).toList());
        List<SubOrderView> subViews = subs.stream()
                .map(sub -> new SubOrderView(sub.getSubOrderNo(), sub.getShopId(), sub.getShopName(),
                        sub.getAmount(), sub.getStatus(),
                        itemsBySubId.getOrDefault(sub.getId(), List.of()).stream()
                                .map(item -> toItemView(item, reviewedIds)).toList()))
                .toList();
        return new OrderDetailView(order.getOrderNo(), order.getStatus(),
                order.getTotalAmount(), order.getPayAmount(),
                order.getReceiver(), order.getPhone(), order.getFullAddress(),
                order.getCreateTime(), order.getExpireTime(), subViews);
    }

    /** 按主订单 ID 聚合订单项（列表用：跨子订单扁平化）。 */
    private Map<Long, List<OrderItemEntity>> itemsByOrderId(List<Long> orderIds) {
        List<SubOrderEntity> subs = subOrderMapper.selectList(Wrappers.<SubOrderEntity>lambdaQuery()
                .in(SubOrderEntity::getOrderId, orderIds));
        Map<Long, Long> orderIdBySubId = subs.stream()
                .collect(Collectors.toMap(SubOrderEntity::getId, SubOrderEntity::getOrderId));
        Map<Long, List<OrderItemEntity>> itemsBySubId = itemsBySubId(
                subs.stream().map(SubOrderEntity::getId).toList());
        return itemsBySubId.entrySet().stream()
                .collect(Collectors.groupingBy(e -> orderIdBySubId.get(e.getKey()),
                        Collectors.flatMapping(e -> e.getValue().stream(), Collectors.toList())));
    }

    /** 按子订单 ID 聚合订单项。 */
    private Map<Long, List<OrderItemEntity>> itemsBySubId(List<Long> subIds) {
        if (subIds.isEmpty()) {
            return Map.of();
        }
        return orderItemMapper.selectList(Wrappers.<OrderItemEntity>lambdaQuery()
                        .in(OrderItemEntity::getSubOrderId, subIds))
                .stream().collect(Collectors.groupingBy(OrderItemEntity::getSubOrderId));
    }

    /** 已评价的订单项 ID 集合（M10）。 */
    private java.util.Set<Long> reviewedItemIds(List<OrderItemEntity> items) {
        if (items.isEmpty()) {
            return java.util.Set.of();
        }
        return productReviewMapper.selectList(Wrappers.<ProductReviewEntity>lambdaQuery()
                        .in(ProductReviewEntity::getOrderItemId,
                                items.stream().map(OrderItemEntity::getId).toList())
                        .select(ProductReviewEntity::getOrderItemId))
                .stream().map(ProductReviewEntity::getOrderItemId).collect(Collectors.toSet());
    }

    private OrderItemView toItemView(OrderItemEntity entity, java.util.Set<Long> reviewedIds) {
        return new OrderItemView(entity.getId(), entity.getSpuId(), entity.getSkuId(),
                entity.getProductName(), entity.getMainImg(), entity.getSpecJson(),
                entity.getPrice(), entity.getQuantity(), reviewedIds.contains(entity.getId()));
    }
}
