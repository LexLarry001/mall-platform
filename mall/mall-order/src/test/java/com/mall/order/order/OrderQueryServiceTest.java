package com.mall.order.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.ProductReviewEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.ProductReviewMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.dto.OrderDetailView;
import com.mall.order.order.dto.OrderSummaryView;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 订单查询服务单元测试（mapper 全 mock）。
 *
 * <p>钉死 plan Task 4 语义：列表按本人过滤（status 可选）+ 订单项聚合；
 * 详情主+子+项聚合，仅本人可见——越权/不存在统一"订单不存在"，不泄露存在性。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    private static final Long USER_ID = 101L;
    private static final Long OTHER_USER_ID = 202L;
    private static final Long ORDER_ID = 9001L;
    private static final String ORDER_NO = "M9001";

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private SubOrderMapper subOrderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductReviewMapper productReviewMapper;

    private OrderQueryService orderQueryService;

    /** lambda 包装器需实体 TableInfo 缓存（同 AddressServiceTest 先例）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, SubOrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, OrderItemEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProductReviewEntity.class);
    }

    @BeforeEach
    void setUp() {
        orderQueryService = new OrderQueryService(orderMapper, subOrderMapper, orderItemMapper,
                productReviewMapper);
    }

    /** 列表：实体映射为摘要视图，跨子订单的订单项聚合到 items。 */
    @Test
    void list_maps_orders_with_aggregated_items() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(ORDER_ID, ORDER_NO, 0)));
        when(subOrderMapper.selectList(any())).thenReturn(List.of(
                sub(8001L, ORDER_ID, 11L, "店铺甲", "25.00"),
                sub(8002L, ORDER_ID, 22L, "店铺乙", "15.00")));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                orderItem(7001L, 8001L, 5001L, "原木杯", "10.00", 2),
                orderItem(7002L, 8001L, 5002L, "胡桃木盘", "5.00", 1),
                orderItem(7003L, 8002L, 5003L, "橡木凳", "7.50", 2)));

        List<OrderSummaryView> list = orderQueryService.list(USER_ID, null);

        assertThat(list).hasSize(1);
        OrderSummaryView view = list.get(0);
        assertThat(view.orderNo()).isEqualTo(ORDER_NO);
        assertThat(view.status()).isZero();
        assertThat(view.totalAmount()).isEqualByComparingTo("60.00");
        assertThat(view.items()).hasSize(3);
        assertThat(view.items().get(0).productName()).isEqualTo("原木杯");
        assertThat(view.items().get(2).price()).isEqualByComparingTo("7.50");
    }

    /** 列表：无订单 → 空列表，不查子订单。 */
    @Test
    void list_empty_returns_empty() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertThat(orderQueryService.list(USER_ID, 0)).isEmpty();
    }

    /** 详情：主+子+项三级聚合。 */
    @Test
    void detail_aggregates_sub_orders_and_items() {
        when(orderMapper.selectOne(any())).thenReturn(order(ORDER_ID, ORDER_NO, 0));
        when(subOrderMapper.selectList(any())).thenReturn(List.of(
                sub(8001L, ORDER_ID, 11L, "店铺甲", "25.00"),
                sub(8002L, ORDER_ID, 22L, "店铺乙", "15.00")));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(
                orderItem(7001L, 8001L, 5001L, "原木杯", "10.00", 2),
                orderItem(7002L, 8002L, 5003L, "橡木凳", "7.50", 2)));

        OrderDetailView detail = orderQueryService.detail(USER_ID, ORDER_NO);

        assertThat(detail.orderNo()).isEqualTo(ORDER_NO);
        assertThat(detail.receiver()).isEqualTo("张三");
        assertThat(detail.fullAddress()).isEqualTo("浙江省杭州市西湖区文三路 1 号");
        assertThat(detail.subOrders()).hasSize(2);
        assertThat(detail.subOrders().get(0).subOrderNo()).isEqualTo("S8001");
        assertThat(detail.subOrders().get(0).shopName()).isEqualTo("店铺甲");
        assertThat(detail.subOrders().get(0).amount()).isEqualByComparingTo("25.00");
        assertThat(detail.subOrders().get(0).items()).hasSize(1);
        assertThat(detail.subOrders().get(1).items()).hasSize(1);
        assertThat(detail.subOrders().get(1).items().get(0).productName()).isEqualTo("橡木凳");
    }

    /** 详情：不存在 → "订单不存在"。 */
    @Test
    void detail_not_found_throws() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> orderQueryService.detail(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    /** 详情：他人订单 → "订单不存在"，不泄露存在性。 */
    @Test
    void detail_other_user_throws() {
        OrderEntity order = order(ORDER_ID, ORDER_NO, 0);
        order.setUserId(OTHER_USER_ID);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> orderQueryService.detail(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    // ------------------------------------------------------------------ helpers

    private OrderEntity order(Long id, String orderNo, int status) {
        OrderEntity entity = new OrderEntity();
        entity.setId(id);
        entity.setOrderNo(orderNo);
        entity.setUserId(USER_ID);
        entity.setTotalAmount(new BigDecimal("60.00"));
        entity.setPayAmount(new BigDecimal("60.00"));
        entity.setStatus(status);
        entity.setReceiver("张三");
        entity.setPhone("13800000000");
        entity.setFullAddress("浙江省杭州市西湖区文三路 1 号");
        entity.setExpireTime(LocalDateTime.now().plusMinutes(30));
        entity.setCreateTime(LocalDateTime.now());
        return entity;
    }

    private SubOrderEntity sub(Long id, Long orderId, Long shopId, String shopName, String amount) {
        SubOrderEntity entity = new SubOrderEntity();
        entity.setId(id);
        entity.setSubOrderNo("S" + id);
        entity.setOrderId(orderId);
        entity.setShopId(shopId);
        entity.setShopName(shopName);
        entity.setAmount(new BigDecimal(amount));
        entity.setStatus(0);
        return entity;
    }

    private OrderItemEntity orderItem(Long id, Long subOrderId, Long skuId, String name,
                                      String price, int quantity) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.setId(id);
        entity.setSubOrderId(subOrderId);
        entity.setSpuId(6000L + skuId);
        entity.setSkuId(skuId);
        entity.setProductName(name);
        entity.setMainImg("/img/" + skuId + ".jpg");
        entity.setSpecJson("{}");
        entity.setPrice(new BigDecimal(price));
        entity.setQuantity(quantity);
        return entity;
    }
}
