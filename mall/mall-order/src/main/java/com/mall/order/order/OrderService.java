package com.mall.order.order;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.address.dto.AddressView;
import com.mall.order.cart.CartService;
import com.mall.order.cart.dto.CartItemView;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.entity.UserAddressEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.mapper.UserAddressMapper;
import com.mall.order.order.dto.SettleView;
import com.mall.product.mapper.ProductSkuMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算下单服务（plan Task 3：核心事务）。
 *
 * <p>下单不变量：</p>
 * <ul>
 *   <li>防重锁 {@code mall:order:submit:{userId}} SET NX EX 5s，未抢到即"请勿重复提交"。</li>
 *   <li>地址归属校验：越权/不存在统一"地址不存在"，不泄露存在性。</li>
 *   <li>价格/库存以库为准：库存条件更新 {@code stock>=?}，行数 0 即超卖，整单回滚。</li>
 *   <li>主订单写地址快照 + expire_time=now+{@code mall.order.pay-timeout-minutes}。</li>
 *   <li>按店铺拆子订单（一店一单，组间顺序=购物车勾选顺序）；订单项写快照。</li>
 *   <li>金额校验：sum(sub)==main，不等抛异常回滚（兜底，正常路径不会触发）。</li>
 *   <li>成功后清购物车已勾选项 + ZSet {@code mall:order:timeout} 写超时锚点。</li>
 * </ul>
 *
 * <p>库存扣减与订单落库在同一事务；清购物车/ZSet 在事务提交后（Redis 不参与本地事务，
 * 失败仅影响购物车残留，可接受，plan 决策 1 同构取舍）。</p>
 */
@Service
public class OrderService {

    /** 超时关单 ZSet 键：member=主订单号，score=支付截止时间戳。 */
    public static final String TIMEOUT_KEY = "mall:order:timeout";

    /** 防重复提交锁键前缀。 */
    public static final String SUBMIT_LOCK_PREFIX = "mall:order:submit:";

    private static final Duration SUBMIT_LOCK_TTL = Duration.ofSeconds(5);

    private final OrderMapper orderMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserAddressMapper addressMapper;
    private final ProductSkuMapper skuMapper;
    private final CartService cartService;
    private final StringRedisTemplate redis;
    private final long payTimeoutMinutes;

    public OrderService(OrderMapper orderMapper, SubOrderMapper subOrderMapper,
                        OrderItemMapper orderItemMapper, UserAddressMapper addressMapper,
                        ProductSkuMapper skuMapper, CartService cartService,
                        StringRedisTemplate redis,
                        @Value("${mall.order.pay-timeout-minutes:30}") long payTimeoutMinutes) {
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.orderItemMapper = orderItemMapper;
        this.addressMapper = addressMapper;
        this.skuMapper = skuMapper;
        this.cartService = cartService;
        this.redis = redis;
        this.payTimeoutMinutes = payTimeoutMinutes;
    }

    /**
     * 结算页数据：勾选购物车项 + 默认地址 + 金额试算，实时校验库存（以库为准）。
     * 勾选项为空（或全部失效）→ 业务错误"没有可结算的商品"。
     */
    public SettleView settle(Long userId) {
        List<CartItemView> items = cartService.listChecked(userId);
        if (items.isEmpty()) {
            throw new BizException("没有可结算的商品");
        }
        for (CartItemView item : items) {
            if (item.stock() == null || item.stock() < item.quantity()) {
                throw new BizException("商品【" + item.productName() + "】库存不足");
            }
        }
        UserAddressEntity defaultAddr = addressMapper.selectOne(Wrappers.<UserAddressEntity>lambdaQuery()
                .eq(UserAddressEntity::getUserId, userId)
                .eq(UserAddressEntity::getIsDefault, 1)
                .last("LIMIT 1"));
        return new SettleView(defaultAddr == null ? null : toAddressView(defaultAddr), items, totalOf(items));
    }

    /**
     * 提交订单：防重锁 → 地址/库存校验 → 主订单+子订单+订单项 → 金额校验 →
     * 清购物车勾选 → ZSet 写超时。返回主订单号。
     */
    @Transactional
    public String submit(Long userId, Long addressId) {
        String lockKey = SUBMIT_LOCK_PREFIX + userId;
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(lockKey, "1", SUBMIT_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException("请勿重复提交");
        }

        UserAddressEntity address = addressMapper.selectById(addressId);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BizException("地址不存在");
        }

        List<CartItemView> items = cartService.listChecked(userId);
        if (items.isEmpty()) {
            throw new BizException("没有可结算的商品");
        }

        // 库存条件扣减（以库为准）；行数 0 即超卖，抛异常整单回滚
        for (CartItemView item : items) {
            int rows = skuMapper.deductStock(item.skuId(), item.quantity());
            if (rows == 0) {
                throw new BizException("商品【" + item.productName() + "】库存不足");
            }
        }

        // 主订单（地址快照 + 支付截止时间）
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(payTimeoutMinutes);
        BigDecimal total = totalOf(items);
        OrderEntity order = new OrderEntity();
        order.setOrderNo("M" + IdWorker.getId());
        order.setUserId(userId);
        order.setTotalAmount(total);
        order.setPayAmount(total);
        order.setStatus(0);
        order.setReceiver(address.getReceiver());
        order.setPhone(address.getPhone());
        order.setFullAddress(address.getProvince() + address.getCity()
                + address.getDistrict() + address.getDetail());
        order.setExpireTime(expireTime);
        orderMapper.insert(order);

        // 按店铺拆子订单（保持购物车勾选顺序，一店一单）
        Map<Long, List<CartItemView>> byShop = new LinkedHashMap<>();
        for (CartItemView item : items) {
            byShop.computeIfAbsent(item.shopId(), k -> new ArrayList<>()).add(item);
        }
        BigDecimal subSum = BigDecimal.ZERO;
        for (Map.Entry<Long, List<CartItemView>> group : byShop.entrySet()) {
            List<CartItemView> shopItems = group.getValue();
            BigDecimal shopAmount = totalOf(shopItems);
            SubOrderEntity sub = new SubOrderEntity();
            sub.setSubOrderNo("S" + IdWorker.getId());
            sub.setOrderId(order.getId());
            sub.setShopId(group.getKey());
            sub.setShopName(shopItems.get(0).shopName());
            sub.setAmount(shopAmount);
            sub.setStatus(0);
            subOrderMapper.insert(sub);

            for (CartItemView item : shopItems) {
                OrderItemEntity orderItem = new OrderItemEntity();
                orderItem.setSubOrderId(sub.getId());
                orderItem.setSpuId(item.spuId());
                orderItem.setSkuId(item.skuId());
                orderItem.setProductName(item.productName());
                orderItem.setMainImg(item.skuImg());
                orderItem.setSpecJson(item.specJson());
                orderItem.setPrice(item.price());
                orderItem.setQuantity(item.quantity());
                orderItemMapper.insert(orderItem);
            }
            subSum = subSum.add(shopAmount);
        }

        // 金额校验（兜底）：不等抛异常整单回滚
        if (subSum.compareTo(total) != 0) {
            throw new BizException("订单金额校验失败");
        }

        // 事务内清购物车勾选 + 写超时锚点（Redis 不参与回滚；失败仅残留购物车，可接受）
        cartService.removeChecked(userId);
        redis.opsForZSet().add(TIMEOUT_KEY, order.getOrderNo(),
                expireTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());

        return order.getOrderNo();
    }

    /**
     * 买家取消订单（plan Task 4）：仅本人 + 仅待支付（0）。
     * 越权/不存在统一"订单不存在"；非待支付"当前状态不可取消"。
     * 级联：子订单→4 + 回补全部订单项库存 + ZSet 移除锚点。
     */
    @Transactional
    public void cancelByUser(Long userId, String orderNo) {
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException("订单不存在");
        }
        if (order.getStatus() != 0) {
            throw new BizException("当前状态不可取消");
        }
        if (!doCancel(order)) {
            throw new BizException("当前状态不可取消");
        }
    }

    /**
     * 超时关单（plan Task 4 Job 语义，幂等）：仅 0→3 生效；
     * 订单已终态或不存在时仅清 ZSet 残留锚点。
     */
    @Transactional
    public void cancelTimeout(String orderNo) {
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || order.getStatus() != 0) {
            redis.opsForZSet().remove(TIMEOUT_KEY, orderNo);
            return;
        }
        doCancel(order);
    }

    /**
     * 关单内核：条件更新 {@code status 0→3}（行数 0 即已被并发处理，幂等返回 false）→
     * 子订单 0→4 → 回补全部订单项库存 → ZSet 移除锚点。
     *
     * @return 本次是否实际执行了关单
     */
    private boolean doCancel(OrderEntity order) {
        int rows = orderMapper.update(null, Wrappers.<OrderEntity>lambdaUpdate()
                .set(OrderEntity::getStatus, 3)
                .eq(OrderEntity::getId, order.getId())
                .eq(OrderEntity::getStatus, 0));
        if (rows == 0) {
            redis.opsForZSet().remove(TIMEOUT_KEY, order.getOrderNo());
            return false;
        }
        subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, 4)
                .eq(SubOrderEntity::getOrderId, order.getId())
                .eq(SubOrderEntity::getStatus, 0));
        List<SubOrderEntity> subs = subOrderMapper.selectList(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getOrderId, order.getId()));
        if (!subs.isEmpty()) {
            List<OrderItemEntity> items = orderItemMapper.selectList(
                    Wrappers.<OrderItemEntity>lambdaQuery()
                            .in(OrderItemEntity::getSubOrderId,
                                    subs.stream().map(SubOrderEntity::getId).toList()));
            for (OrderItemEntity item : items) {
                skuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            }
        }
        redis.opsForZSet().remove(TIMEOUT_KEY, order.getOrderNo());
        return true;
    }

    private BigDecimal totalOf(List<CartItemView> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (CartItemView item : items) {
            total = total.add(item.price().multiply(BigDecimal.valueOf(item.quantity())));
        }
        return total;
    }

    private AddressView toAddressView(UserAddressEntity entity) {
        return new AddressView(entity.getId(), entity.getReceiver(), entity.getPhone(),
                entity.getProvince(), entity.getCity(), entity.getDistrict(),
                entity.getDetail(), entity.getIsDefault());
    }
}
