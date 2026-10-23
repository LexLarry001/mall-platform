package com.mall.order.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 结算下单服务单元测试（mapper / CartService / Redis 全 mock）。
 *
 * <p>钉死 plan Task 3 语义：防重锁 → 地址归属校验 → 价格以库为准 → 库存条件更新（行数 0 即超卖）
 * → 主订单（地址快照 + expire_time=now+30min）→ 按店铺拆子订单 → 订单项快照 → 金额校验
 * → 清购物车已勾选 → ZSet 写超时。单店不拆单、跨店拆单金额齐。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long USER_ID = 101L;
    private static final Long OTHER_USER_ID = 202L;
    private static final Long ADDRESS_ID = 301L;
    private static final Long ORDER_ID = 9001L;
    private static final int TIMEOUT_MINUTES = 30;

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
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private OrderService orderService;

    /** 默认地址查询走 lambdaQuery，纯 mock 单测需实体 TableInfo 缓存（同 AddressServiceTest 先例）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserAddressEntity.class);
        TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, SubOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, OrderItemEntity.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        orderService = new OrderService(orderMapper, subOrderMapper, orderItemMapper,
                addressMapper, skuMapper, cartService, redis, TIMEOUT_MINUTES);
    }

    // ------------------------------------------------------------------ settle

    /** 结算：勾选项为空（或全部失效）→ 业务错误"没有可结算的商品"。 */
    @Test
    void settle_no_checked_items_throws() {
        when(cartService.listChecked(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.settle(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessage("没有可结算的商品");
    }

    /** 结算：勾选数量超过实时库存 → 业务错误"库存不足"（价格/库存以库为准的实时校验）。 */
    @Test
    void settle_stock_insufficient_throws() {
        when(cartService.listChecked(USER_ID))
                .thenReturn(List.of(item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 1, 2)));

        assertThatThrownBy(() -> orderService.settle(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");
    }

    /** 结算：返回勾选项 + 默认地址 + 金额试算（sum(price*quantity)，运费固定 0）。 */
    @Test
    void settle_returns_default_address_and_total() {
        List<CartItemView> items = List.of(
                item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 10, 2),
                item(5002L, 6002L, 11L, "店铺甲", "胡桃木盘", "5.00", 10, 1));
        when(cartService.listChecked(USER_ID)).thenReturn(items);
        when(addressMapper.selectOne(any())).thenReturn(addressOf(USER_ID));

        SettleView view = orderService.settle(USER_ID);

        assertThat(view.items()).isEqualTo(items);
        assertThat(view.totalAmount()).isEqualByComparingTo("25.00");
        AddressView addr = view.defaultAddress();
        assertThat(addr).isNotNull();
        assertThat(addr.id()).isEqualTo(ADDRESS_ID);
        assertThat(addr.receiver()).isEqualTo("张三");
    }

    /** 结算：买家无默认地址时 defaultAddress 为 null（前端引导新增），不报错。 */
    @Test
    void settle_without_default_address_returns_null_address() {
        when(cartService.listChecked(USER_ID))
                .thenReturn(List.of(item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 10, 1)));
        when(addressMapper.selectOne(any())).thenReturn(null);

        SettleView view = orderService.settle(USER_ID);

        assertThat(view.defaultAddress()).isNull();
        assertThat(view.totalAmount()).isEqualByComparingTo("10.00");
    }

    // ------------------------------------------------------------------ submit

    /** 下单：防重锁未抢到 → 业务错误"请勿重复提交"，不落任何数据。 */
    @Test
    void submit_duplicate_lock_throws() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> orderService.submit(USER_ID, ADDRESS_ID))
                .isInstanceOf(BizException.class)
                .hasMessage("请勿重复提交");
        verifyNoInteractions(orderMapper, subOrderMapper, orderItemMapper, cartService);
    }

    /** 下单：地址属于他人（或不存在）→ 业务错误"地址不存在"，不泄露存在性。 */
    @Test
    void submit_address_of_other_user_throws() {
        stubLockAcquired();
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(addressOf(OTHER_USER_ID));

        assertThatThrownBy(() -> orderService.submit(USER_ID, ADDRESS_ID))
                .isInstanceOf(BizException.class)
                .hasMessage("地址不存在");
        verify(orderMapper, never()).insert(any(OrderEntity.class));
    }

    /** 下单：勾选项为空 → 业务错误"没有可结算的商品"。 */
    @Test
    void submit_no_checked_items_throws() {
        stubLockAcquired();
        stubOwnedAddress();
        when(cartService.listChecked(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.submit(USER_ID, ADDRESS_ID))
                .isInstanceOf(BizException.class)
                .hasMessage("没有可结算的商品");
        verify(orderMapper, never()).insert(any(OrderEntity.class));
    }

    /** 下单：库存条件更新行数 0（超卖）→ 业务错误"库存不足"，主/子订单均不落库。 */
    @Test
    void submit_oversell_throws_and_writes_nothing() {
        stubLockAcquired();
        stubOwnedAddress();
        when(cartService.listChecked(USER_ID))
                .thenReturn(List.of(item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 5, 3)));
        when(skuMapper.deductStock(5001L, 3)).thenReturn(0);

        assertThatThrownBy(() -> orderService.submit(USER_ID, ADDRESS_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");
        verify(orderMapper, never()).insert(any(OrderEntity.class));
        verify(subOrderMapper, never()).insert(any(SubOrderEntity.class));
        verify(orderItemMapper, never()).insert(any(OrderItemEntity.class));
    }

    /** 下单：单店商品不拆单——1 主 1 子，金额一致，主订单带地址快照与支付截止时间。 */
    @Test
    void submit_single_shop_creates_one_sub_order() {
        stubLockAcquired();
        stubOwnedAddress();
        stubInsertAssignsIds();
        List<CartItemView> items = List.of(
                item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 10, 2),
                item(5002L, 6002L, 11L, "店铺甲", "胡桃木盘", "5.00", 10, 1));
        when(cartService.listChecked(USER_ID)).thenReturn(items);
        when(skuMapper.deductStock(5001L, 2)).thenReturn(1);
        when(skuMapper.deductStock(5002L, 1)).thenReturn(1);

        String orderNo = orderService.submit(USER_ID, ADDRESS_ID);

        // 主订单
        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).insert(orderCaptor.capture());
        OrderEntity order = orderCaptor.getValue();
        assertThat(orderNo).isEqualTo(order.getOrderNo()).startsWith("M");
        assertThat(order.getUserId()).isEqualTo(USER_ID);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(order.getPayAmount()).isEqualByComparingTo("25.00");
        assertThat(order.getStatus()).isZero();
        assertThat(order.getReceiver()).isEqualTo("张三");
        assertThat(order.getPhone()).isEqualTo("13800000000");
        assertThat(order.getFullAddress()).isEqualTo("浙江省杭州市西湖区文三路 1 号");
        assertThat(order.getExpireTime()).isBetween(
                LocalDateTime.now().plusMinutes(TIMEOUT_MINUTES - 1),
                LocalDateTime.now().plusMinutes(TIMEOUT_MINUTES + 1));

        // 子订单：恰好 1 个，金额 == 主订单
        ArgumentCaptor<SubOrderEntity> subCaptor = ArgumentCaptor.forClass(SubOrderEntity.class);
        verify(subOrderMapper).insert(subCaptor.capture());
        SubOrderEntity sub = subCaptor.getValue();
        assertThat(sub.getSubOrderNo()).startsWith("S");
        assertThat(sub.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(sub.getShopId()).isEqualTo(11L);
        assertThat(sub.getShopName()).isEqualTo("店铺甲");
        assertThat(sub.getAmount()).isEqualByComparingTo("25.00");
        assertThat(sub.getStatus()).isZero();

        // 订单项：2 条快照，归属该子订单
        ArgumentCaptor<OrderItemEntity> itemCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        verify(orderItemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        List<OrderItemEntity> orderItems = itemCaptor.getAllValues();
        assertThat(orderItems).allSatisfy(oi -> assertThat(oi.getSubOrderId()).isEqualTo(sub.getId()));
        assertThat(orderItems.get(0).getSkuId()).isEqualTo(5001L);
        assertThat(orderItems.get(0).getSpuId()).isEqualTo(6001L);
        assertThat(orderItems.get(0).getProductName()).isEqualTo("原木杯");
        assertThat(orderItems.get(0).getPrice()).isEqualByComparingTo("10.00");
        assertThat(orderItems.get(0).getQuantity()).isEqualTo(2);
        assertThat(orderItems.get(1).getSkuId()).isEqualTo(5002L);
    }

    /** 下单：跨店拆单——1 主 2 子，按店铺分组，sum(sub)==main。 */
    @Test
    void submit_cross_shop_splits_and_amounts_add_up() {
        stubLockAcquired();
        stubOwnedAddress();
        stubInsertAssignsIds();
        List<CartItemView> items = List.of(
                item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 10, 2),
                item(5002L, 6002L, 22L, "店铺乙", "橡木凳", "7.50", 10, 2),
                item(5003L, 6003L, 11L, "店铺甲", "胡桃木盘", "5.00", 10, 1));
        when(cartService.listChecked(USER_ID)).thenReturn(items);
        when(skuMapper.deductStock(5001L, 2)).thenReturn(1);
        when(skuMapper.deductStock(5002L, 2)).thenReturn(1);
        when(skuMapper.deductStock(5003L, 1)).thenReturn(1);

        orderService.submit(USER_ID, ADDRESS_ID);

        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).insert(orderCaptor.capture());
        BigDecimal mainTotal = orderCaptor.getValue().getTotalAmount();
        assertThat(mainTotal).isEqualByComparingTo("40.00");

        ArgumentCaptor<SubOrderEntity> subCaptor = ArgumentCaptor.forClass(SubOrderEntity.class);
        verify(subOrderMapper, org.mockito.Mockito.times(2)).insert(subCaptor.capture());
        List<SubOrderEntity> subs = subCaptor.getAllValues();
        // 店铺甲组（先出现）金额 25.00，店铺乙组 15.00，合计 == 主订单
        assertThat(subs.get(0).getShopId()).isEqualTo(11L);
        assertThat(subs.get(0).getAmount()).isEqualByComparingTo("25.00");
        assertThat(subs.get(1).getShopId()).isEqualTo(22L);
        assertThat(subs.get(1).getShopName()).isEqualTo("店铺乙");
        assertThat(subs.get(1).getAmount()).isEqualByComparingTo("15.00");
        assertThat(subs.get(0).getAmount().add(subs.get(1).getAmount()))
                .isEqualByComparingTo(mainTotal);

        // 订单项归属：店铺甲 2 条、店铺乙 1 条
        ArgumentCaptor<OrderItemEntity> itemCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        verify(orderItemMapper, org.mockito.Mockito.times(3)).insert(itemCaptor.capture());
        List<OrderItemEntity> orderItems = itemCaptor.getAllValues();
        assertThat(orderItems.stream().filter(oi -> oi.getSubOrderId().equals(subs.get(0).getId())))
                .hasSize(2);
        assertThat(orderItems.stream().filter(oi -> oi.getSubOrderId().equals(subs.get(1).getId())))
                .hasSize(1);
    }

    /** 下单成功后：清购物车已勾选项 + ZSet 写入超时关单锚点（member=主订单号，score=支付截止时间戳）。 */
    @Test
    void submit_clears_cart_and_writes_timeout_zset() {
        stubLockAcquired();
        stubOwnedAddress();
        stubInsertAssignsIds();
        when(cartService.listChecked(USER_ID))
                .thenReturn(List.of(item(5001L, 6001L, 11L, "店铺甲", "原木杯", "10.00", 10, 1)));
        when(skuMapper.deductStock(5001L, 1)).thenReturn(1);

        String orderNo = orderService.submit(USER_ID, ADDRESS_ID);

        verify(cartService).removeChecked(USER_ID);
        ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);
        verify(zSetOps).add(eq(OrderService.TIMEOUT_KEY),
                argThat(member -> member.equals(orderNo)), scoreCaptor.capture());
        double expected = System.currentTimeMillis() + Duration.ofMinutes(TIMEOUT_MINUTES).toMillis();
        assertThat(scoreCaptor.getValue()).isCloseTo(expected, within(10_000.0));
    }

    // ------------------------------------------------------------------ cancel

    /** 取消：成功路径——主订单 0→3、子订单→4、逐项回补库存、ZSet 移除。 */
    @Test
    void cancelByUser_success_cascades_and_restores_stock() {
        OrderEntity order = ownedOrder(0);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(subOrderMapper.selectList(any())).thenReturn(List.of(subOf(order.getId(), 8001L)));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                cancelItem(5001L, 2), cancelItem(5002L, 1)));

        orderService.cancelByUser(USER_ID, order.getOrderNo());

        verify(orderMapper).update(any(), any());
        verify(subOrderMapper).update(any(), any());
        verify(skuMapper).restoreStock(5001L, 2);
        verify(skuMapper).restoreStock(5002L, 1);
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, order.getOrderNo());
    }

    /** 取消：订单不存在 → "订单不存在"。 */
    @Test
    void cancelByUser_not_found_throws() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> orderService.cancelByUser(USER_ID, "M_x"))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    /** 取消：他人订单 → "订单不存在"，不泄露存在性。 */
    @Test
    void cancelByUser_other_user_throws() {
        OrderEntity order = ownedOrder(0);
        order.setUserId(OTHER_USER_ID);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> orderService.cancelByUser(USER_ID, order.getOrderNo()))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    /** 取消：非待支付状态 → "当前状态不可取消"。 */
    @Test
    void cancelByUser_non_pending_throws() {
        when(orderMapper.selectOne(any())).thenReturn(ownedOrder(1));

        assertThatThrownBy(() -> orderService.cancelByUser(USER_ID, "M9001"))
                .isInstanceOf(BizException.class)
                .hasMessage("当前状态不可取消");
        verify(skuMapper, never()).restoreStock(any(), any(Integer.class));
    }

    /** 取消：并发已被处理（条件更新行数 0）→ "当前状态不可取消"，不回补库存。 */
    @Test
    void cancelByUser_concurrent_lost_throws() {
        when(orderMapper.selectOne(any())).thenReturn(ownedOrder(0));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService.cancelByUser(USER_ID, "M9001"))
                .isInstanceOf(BizException.class)
                .hasMessage("当前状态不可取消");
        verify(skuMapper, never()).restoreStock(any(), any(Integer.class));
    }

    // ------------------------------------------------------------------ helpers

    private OrderEntity ownedOrder(int status) {
        OrderEntity entity = new OrderEntity();
        entity.setId(ORDER_ID);
        entity.setOrderNo("M9001");
        entity.setUserId(USER_ID);
        entity.setStatus(status);
        entity.setExpireTime(LocalDateTime.now().plusMinutes(30));
        return entity;
    }

    private SubOrderEntity subOf(Long orderId, Long subId) {
        SubOrderEntity entity = new SubOrderEntity();
        entity.setId(subId);
        entity.setOrderId(orderId);
        entity.setStatus(0);
        return entity;
    }

    private OrderItemEntity cancelItem(Long skuId, int quantity) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.setSubOrderId(8001L);
        entity.setSkuId(skuId);
        entity.setQuantity(quantity);
        return entity;
    }

    private void stubLockAcquired() {
        when(valueOps.setIfAbsent(eq("mall:order:submit:" + USER_ID), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    private void stubOwnedAddress() {
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(addressOf(USER_ID));
    }

    /** 模拟 MyBatis-Plus ASSIGN_ID 运行期行为：insert 时回填雪花 ID。 */
    private void stubInsertAssignsIds() {
        doAnswer(inv -> {
            ((OrderEntity) inv.getArgument(0)).setId(ORDER_ID);
            return 1;
        }).when(orderMapper).insert(any(OrderEntity.class));
        AtomicLong subIdSeq = new AtomicLong(8001L);
        doAnswer(inv -> {
            ((SubOrderEntity) inv.getArgument(0)).setId(subIdSeq.getAndIncrement());
            return 1;
        }).when(subOrderMapper).insert(any(SubOrderEntity.class));
    }

    private CartItemView item(long skuId, long spuId, long shopId, String shopName, String name,
                              String price, int stock, int quantity) {
        return new CartItemView(skuId, spuId, shopId, shopName, name, "/img/" + skuId + ".jpg",
                "{\"规格\":\"默认\"}", new BigDecimal(price), stock, quantity, true, false);
    }

    private UserAddressEntity addressOf(Long userId) {
        UserAddressEntity entity = new UserAddressEntity();
        entity.setId(ADDRESS_ID);
        entity.setUserId(userId);
        entity.setReceiver("张三");
        entity.setPhone("13800000000");
        entity.setProvince("浙江省");
        entity.setCity("杭州市");
        entity.setDistrict("西湖区");
        entity.setDetail("文三路 1 号");
        entity.setIsDefault(1);
        return entity;
    }
}
