package com.mall.order.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.RefundOrderEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.RefundOrderMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.refund.dto.RefundApplyRequest;
import com.mall.order.refund.dto.RefundView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M6 Task 2：买家退款申请/查询纯单元测试（mock Mapper，覆盖守卫分支与金额快照）。
 */
@ExtendWith(MockitoExtension.class)
class RefundApplyServiceTest {

    private static final Long USER_A = 1001L;
    private static final Long USER_B = 1002L;
    private static final Long ORDER_ID = 2001L;
    private static final Long SUB_ID = 3001L;
    private static final Long ITEM_ID = 4001L;
    private static final Long SHOP_ID = 5001L;
    private static final String ORDER_NO = "M2001";
    private static final String SUB_NO = "S3001";

    @Mock
    private RefundOrderMapper refundOrderMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private SubOrderMapper subOrderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Captor
    private ArgumentCaptor<RefundOrderEntity> refundCaptor;

    private RefundService service;

    @BeforeEach
    void setUp() {
        service = new RefundService(refundOrderMapper, orderMapper, subOrderMapper,
                orderItemMapper, new ObjectMapper());
    }

    private OrderEntity order(Long userId) {
        OrderEntity order = new OrderEntity();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setUserId(userId);
        return order;
    }

    private SubOrderEntity sub(int status) {
        SubOrderEntity sub = new SubOrderEntity();
        sub.setId(SUB_ID);
        sub.setSubOrderNo(SUB_NO);
        sub.setOrderId(ORDER_ID);
        sub.setShopId(SHOP_ID);
        sub.setStatus(status);
        return sub;
    }

    private OrderItemEntity item() {
        OrderItemEntity item = new OrderItemEntity();
        item.setId(ITEM_ID);
        item.setSubOrderId(SUB_ID);
        item.setSpuId(11L);
        item.setSkuId(22L);
        item.setProductName("木椅");
        item.setPrice(new BigDecimal("10.00"));
        item.setQuantity(2);
        return item;
    }

    private RefundApplyRequest request(String reason, List<String> evidence) {
        return new RefundApplyRequest(ORDER_NO, SUB_NO, ITEM_ID, reason, evidence);
    }

    private void stubHappyPath(int subStatus) {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_A));
        when(subOrderMapper.selectOne(any())).thenReturn(sub(subStatus));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item());
        when(refundOrderMapper.selectList(any())).thenReturn(List.of());
        when(subOrderMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void apply_success_snapshots_amount_and_moves_sub_order_to_refunding() {
        stubHappyPath(1);

        String refundNo = service.apply(USER_A, request("商品质量问题", List.of("https://img/a.jpg")));

        assertThat(refundNo).startsWith(RefundService.REFUND_NO_PREFIX);
        verify(refundOrderMapper).insert(refundCaptor.capture());
        RefundOrderEntity saved = refundCaptor.getValue();
        assertThat(saved.getRefundAmount()).isEqualByComparingTo("20.00");
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(saved.getSubStatusBefore()).isEqualTo(1);
        assertThat(saved.getShopId()).isEqualTo(SHOP_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_A);
        assertThat(saved.getEvidenceImages()).isEqualTo("[\"https://img/a.jpg\"]");
        verify(subOrderMapper).update(any(), any());
    }

    @Test
    void apply_rejects_other_users_order() {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_B));

        assertThatThrownBy(() -> service.apply(USER_A, request("不想要了", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
        verify(refundOrderMapper, never()).insert(any(RefundOrderEntity.class));
    }

    @Test
    void apply_rejects_sub_order_not_completed_status() {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_A));
        when(subOrderMapper.selectOne(any())).thenReturn(sub(3));

        assertThatThrownBy(() -> service.apply(USER_A, request("不想要了", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("当前状态不可申请退款");
    }

    @Test
    void apply_rejects_refund_already_pending() {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_A));
        when(subOrderMapper.selectOne(any())).thenReturn(sub(1));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item());
        RefundOrderEntity existing = new RefundOrderEntity();
        existing.setStatus(RefundStatus.PENDING);
        when(refundOrderMapper.selectList(any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.apply(USER_A, request("不想要了", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("该商品已申请退款");
    }

    @Test
    void apply_rejects_item_already_refunded() {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_A));
        when(subOrderMapper.selectOne(any())).thenReturn(sub(2));
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item());
        RefundOrderEntity existing = new RefundOrderEntity();
        existing.setStatus(RefundStatus.REFUNDED);
        when(refundOrderMapper.selectList(any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.apply(USER_A, request("不想要了", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("该商品已退款");
    }

    @Test
    void apply_rejects_item_not_in_sub_order() {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_A));
        when(subOrderMapper.selectOne(any())).thenReturn(sub(1));
        OrderItemEntity foreign = item();
        foreign.setSubOrderId(999L);
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(foreign);

        assertThatThrownBy(() -> service.apply(USER_A, request("不想要了", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void apply_rejects_blank_reason_and_too_many_images() {
        assertThatThrownBy(() -> service.apply(USER_A, request("  ", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("请填写退款原因");

        assertThatThrownBy(() -> service.apply(USER_A,
                request("不想要了", List.of("a", "b", "c", "d"))))
                .isInstanceOf(BizException.class)
                .hasMessage("凭证图片最多 3 张");
    }

    @Test
    void apply_fails_when_sub_order_concurrently_changed() {
        stubHappyPath(2);
        when(subOrderMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.apply(USER_A, request("不想要了", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("当前状态不可申请退款");
    }

    @Test
    void list_by_order_and_detail_guard_ownership() {
        when(orderMapper.selectOne(any())).thenReturn(order(USER_B));
        assertThatThrownBy(() -> service.listByOrder(USER_A, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");

        RefundOrderEntity refund = new RefundOrderEntity();
        refund.setRefundNo("RF1");
        refund.setUserId(USER_B);
        refund.setStatus(RefundStatus.REJECTED);
        when(refundOrderMapper.selectOne(any())).thenReturn(refund);
        assertThatThrownBy(() -> service.detail(USER_A, "RF1"))
                .isInstanceOf(BizException.class)
                .hasMessage("退款单不存在");

        RefundView view = service.detail(USER_B, "RF1");
        assertThat(view.refundNo()).isEqualTo("RF1");
        assertThat(view.evidenceImages()).isEmpty();
    }
}
