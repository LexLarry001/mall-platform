package com.mall.order.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.order.cart.CartService;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.mapper.UserAddressMapper;
import com.mall.product.mapper.ProductSkuMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时关单 Job 单元测试（真实 OrderService + mapper/Redis mock，穿透到关单语义）。
 *
 * <p>钉死 plan Task 4 语义：每分钟 ZRANGEBYSCORE 到期关单（仅 0→3 生效，幂等）；
 * 未到期不动；重复扫描不重复回补库存；关单回补全部订单项库存。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderTimeoutJobTest {

    private static final Long ORDER_ID = 9001L;
    private static final String ORDER_NO = "M9001";

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private SubOrderMapper subOrderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private UserAddressMapper addressMapper;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private CartService cartService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private OrderTimeoutJob job;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, SubOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, OrderItemEntity.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        OrderService orderService = new OrderService(orderMapper, subOrderMapper, orderItemMapper,
                addressMapper, skuMapper, cartService, redis, 30);
        job = new OrderTimeoutJob(redis, orderService);
    }

    /** 到期关单：ZSet 扫到到期成员 → 主订单 0→3、子订单→4、逐项回补库存、ZSet 移除。 */
    @Test
    void closes_expired_order_and_restores_stock() {
        when(zSetOps.rangeByScore(eq(OrderService.TIMEOUT_KEY), eq(0.0), anyDouble()))
                .thenReturn(Set.of(ORDER_NO));
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(subOrderMapper.selectList(any())).thenReturn(List.of(sub(8001L), sub(8002L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                item(5001L, 2), item(5002L, 1), item(5003L, 3)));

        job.closeExpired();

        verify(orderMapper).update(any(), any());
        verify(subOrderMapper).update(any(), any());
        verify(skuMapper).restoreStock(5001L, 2);
        verify(skuMapper).restoreStock(5002L, 1);
        verify(skuMapper).restoreStock(5003L, 3);
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    /** 未到期：ZSet 扫不出成员 → 不动任何数据。 */
    @Test
    void leaves_unexpired_alone() {
        when(zSetOps.rangeByScore(eq(OrderService.TIMEOUT_KEY), eq(0.0), anyDouble()))
                .thenReturn(Set.of());

        job.closeExpired();

        verifyNoInteractions(orderMapper, subOrderMapper, orderItemMapper, skuMapper);
    }

    /** 重复扫描幂等：条件更新行数 0（已被关）→ 不重复回补库存。 */
    @Test
    void repeated_scan_idempotent_no_double_restore() {
        when(zSetOps.rangeByScore(eq(OrderService.TIMEOUT_KEY), eq(0.0), anyDouble()))
                .thenReturn(Set.of(ORDER_NO));
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        // 第一次关单成功，第二次并发条件更新行数 0
        when(orderMapper.update(any(), any())).thenReturn(1, 0);
        when(subOrderMapper.selectList(any())).thenReturn(List.of(sub(8001L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(5001L, 2)));

        job.closeExpired();
        job.closeExpired();

        verify(skuMapper, times(1)).restoreStock(5001L, 2);
        verify(zSetOps, times(2)).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    /** 已终态订单（如用户已取消）再次扫到：不动库存，仅清 ZSet 残留锚点。 */
    @Test
    void terminal_order_only_cleans_anchor() {
        OrderEntity cancelled = pendingOrder();
        cancelled.setStatus(3);
        when(zSetOps.rangeByScore(eq(OrderService.TIMEOUT_KEY), eq(0.0), anyDouble()))
                .thenReturn(Set.of(ORDER_NO));
        when(orderMapper.selectOne(any())).thenReturn(cancelled);

        job.closeExpired();

        verify(orderMapper, never()).update(any(), any());
        verify(skuMapper, never()).restoreStock(any(), any(Integer.class));
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    /** 单个成员关单异常不影响其他成员处理。 */
    @Test
    void failure_of_one_does_not_block_others() {
        when(zSetOps.rangeByScore(eq(OrderService.TIMEOUT_KEY), eq(0.0), anyDouble()))
                .thenReturn(Set.of("M_bad", ORDER_NO));
        when(orderMapper.selectOne(any()))
                .thenThrow(new RuntimeException("DB 抖动"))
                .thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(subOrderMapper.selectList(any())).thenReturn(List.of(sub(8001L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(5001L, 2)));

        job.closeExpired();

        verify(skuMapper).restoreStock(5001L, 2);
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    // ------------------------------------------------------------------ helpers

    private OrderEntity pendingOrder() {
        OrderEntity entity = new OrderEntity();
        entity.setId(ORDER_ID);
        entity.setOrderNo(ORDER_NO);
        entity.setUserId(101L);
        entity.setTotalAmount(new BigDecimal("60.00"));
        entity.setPayAmount(new BigDecimal("60.00"));
        entity.setStatus(0);
        entity.setExpireTime(LocalDateTime.now().minusMinutes(1));
        return entity;
    }

    private SubOrderEntity sub(Long id) {
        SubOrderEntity entity = new SubOrderEntity();
        entity.setId(id);
        entity.setSubOrderNo("S" + id);
        entity.setOrderId(ORDER_ID);
        entity.setStatus(0);
        return entity;
    }

    private OrderItemEntity item(Long skuId, int quantity) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.setSubOrderId(8001L);
        entity.setSkuId(skuId);
        entity.setQuantity(quantity);
        return entity;
    }
}
