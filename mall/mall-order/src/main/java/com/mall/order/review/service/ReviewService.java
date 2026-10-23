package com.mall.order.review.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.review.dto.ReviewCreateRequest;
import com.mall.order.review.event.ReviewCreatedEvent;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.user.entity.UserEntity;
import com.mall.user.mapper.UserMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 买家评价写入：资格校验 + 防重 + 快照落库 + 评分聚合。
 *
 * <p>校验顺序：订单项存在 → 归属当前买家 → 子订单已完成 → 未评价过 →
 * 内容/图片边界。唯一键 {@code uk_review_item} 作为并发兜底。</p>
 */
@Service
public class ReviewService {

    private static final int SUB_ORDER_COMPLETED = 3;

    private final ProductReviewMapper reviewMapper;
    private final OrderItemMapper orderItemMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;
    private final UserMapper userMapper;
    private final ReviewAggregateService aggregateService;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewService(ProductReviewMapper reviewMapper,
                         OrderItemMapper orderItemMapper,
                         SubOrderMapper subOrderMapper,
                         OrderMapper orderMapper,
                         ProductMapper productMapper,
                         ShopMapper shopMapper,
                         UserMapper userMapper,
                         ReviewAggregateService aggregateService,
                         ApplicationEventPublisher eventPublisher) {
        this.reviewMapper = reviewMapper;
        this.orderItemMapper = orderItemMapper;
        this.subOrderMapper = subOrderMapper;
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
        this.shopMapper = shopMapper;
        this.userMapper = userMapper;
        this.aggregateService = aggregateService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long create(Long userId, ReviewCreateRequest request) {
        OrderItemEntity item = orderItemMapper.selectById(request.orderItemId());
        if (item == null) {
            throw new BizException("订单项不存在");
        }
        SubOrderEntity subOrder = subOrderMapper.selectById(item.getSubOrderId());
        if (subOrder == null) {
            throw new BizException("子订单不存在");
        }
        OrderEntity order = orderMapper.selectById(subOrder.getOrderId());
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BizException("无权评价该订单");
        }
        if (subOrder.getStatus() == null || subOrder.getStatus() != SUB_ORDER_COMPLETED) {
            throw new BizException("订单完成后才能评价");
        }
        Long reviewed = reviewMapper.selectCount(Wrappers.<ProductReviewEntity>lambdaQuery()
                .eq(ProductReviewEntity::getOrderItemId, request.orderItemId()));
        if (reviewed != null && reviewed > 0) {
            throw new BizException("该商品已评价");
        }

        ProductEntity product = productMapper.selectById(item.getSpuId());
        if (product == null) {
            throw new BizException("商品不存在");
        }
        ShopEntity shop = shopMapper.selectById(product.getShopId());
        UserEntity user = userMapper.selectById(userId);

        String images = joinImages(request.images());
        ProductReviewEntity entity = new ProductReviewEntity();
        entity.setUserId(userId);
        entity.setUserName(user == null ? "买家" : user.getUsername());
        entity.setOrderId(order.getId());
        entity.setSubOrderId(subOrder.getId());
        entity.setOrderItemId(item.getId());
        entity.setSpuId(item.getSpuId());
        entity.setSkuId(item.getSkuId());
        entity.setShopId(product.getShopId());
        entity.setShopName(shop == null ? "" : shop.getName());
        entity.setProductName(item.getProductName());
        entity.setMainImg(item.getMainImg());
        entity.setRating(request.rating());
        entity.setContent(request.content().trim());
        entity.setImages(images);
        entity.setAnonymous(Boolean.TRUE.equals(request.anonymous()) ? 1 : 0);
        entity.setTags("");
        entity.setStatus(1);

        try {
            reviewMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException("该商品已评价");
        }

        aggregateService.refresh(item.getSpuId());
        eventPublisher.publishEvent(new ReviewCreatedEvent(entity.getId()));
        return entity.getId();
    }

    private static String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) return "";
        String joined = images.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .limit(6)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        if (joined.length() > 1000) {
            throw new BizException("评价图地址过长");
        }
        return joined;
    }
}
