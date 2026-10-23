package com.mall.order.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.PaymentRecordEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.PaymentRecordMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.OrderService;
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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 支付成功内核 + 异步回调 + 主动查询单元测试（mapper / AlipayClient / 验签器全 mock，
 * plan Task 3 验收①）。
 *
 * <p>钉死语义：markPaid 条件更新 0→1 幂等（行数 0 不动子订单/流水/ZSet）；
 * notify 验签失败/APPID 不符/金额不符/订单不存在一律 failure，非成功状态 success 不推进，
 * TRADE_SUCCESS 推进 markPaid；query 已支付直返不调网关、TRADE_SUCCESS 推进、
 * WAIT_BUYER_PAY 不推进、网关异常统一文案。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceNotifyTest {

    private static final Long USER_ID = 101L;
    private static final Long OTHER_USER_ID = 202L;
    private static final Long ORDER_ID = 9001L;
    private static final String ORDER_NO = "M123456789012345678";
    private static final String APP_ID = "2021000148635178";
    private static final String TRADE_NO = "2026102122001488880501234567";

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private PaymentRecordMapper paymentRecordMapper;
    @Mock
    private SubOrderMapper subOrderMapper;
    @Mock
    private AlipayClient alipayClient;
    @Mock
    private AlipayNotifyVerifier notifyVerifier;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSetOps;

    private PaymentService paymentService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, PaymentRecordEntity.class);
        TableInfoHelper.initTableInfo(assistant, SubOrderEntity.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        paymentService = new PaymentService(orderMapper, paymentRecordMapper, subOrderMapper,
                alipayClient, notifyVerifier, redis, APP_ID, "http://notify", "http://return", "木集商城订单-");
    }

    private OrderEntity pendingOrder() {
        OrderEntity order = new OrderEntity();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setPayAmount(new BigDecimal("60.00"));
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private Map<String, String> validNotifyParams() {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", ORDER_NO);
        params.put("trade_no", TRADE_NO);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("app_id", APP_ID);
        params.put("total_amount", "60.00");
        params.put("gmt_payment", "2026-10-21 12:00:00");
        params.put("sign", "stub-sign");
        return params;
    }

    // ------------------------------------------------------------------ markPaid

    @Test
    void markPaid_first_call_updates_main_sub_record_and_clears_anchor() {
        when(orderMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        boolean done = paymentService.markPaid(ORDER_NO, TRADE_NO, "raw-body",
                LocalDateTime.of(2026, 10, 21, 12, 0));

        assertThat(done).isTrue();
        verify(subOrderMapper).update(isNull(), any());
        verify(paymentRecordMapper).update(isNull(), any());
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    @Test
    void markPaid_second_call_rows_zero_idempotent_returns_false() {
        when(orderMapper.update(isNull(), any())).thenReturn(0);

        boolean done = paymentService.markPaid(ORDER_NO, TRADE_NO, "raw-body", LocalDateTime.now());

        assertThat(done).isFalse();
        verify(subOrderMapper, never()).update(any(), any());
        verify(paymentRecordMapper, never()).update(any(), any());
        verify(zSetOps, never()).remove(anyString(), any());
    }

    // ------------------------------------------------------------------ handleNotify

    @Test
    void handleNotify_bad_sign_returns_failure() {
        when(notifyVerifier.verify(any())).thenReturn(false);

        assertThat(paymentService.handleNotify(validNotifyParams())).isEqualTo("failure");
        verifyNoInteractions(orderMapper);
    }

    @Test
    void handleNotify_app_id_mismatch_returns_failure() {
        when(notifyVerifier.verify(any())).thenReturn(true);
        Map<String, String> params = validNotifyParams();
        params.put("app_id", "9999999999999999");

        assertThat(paymentService.handleNotify(params)).isEqualTo("failure");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void handleNotify_unknown_order_returns_failure() {
        when(notifyVerifier.verify(any())).thenReturn(true);
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThat(paymentService.handleNotify(validNotifyParams())).isEqualTo("failure");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void handleNotify_amount_mismatch_returns_failure() {
        when(notifyVerifier.verify(any())).thenReturn(true);
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        Map<String, String> params = validNotifyParams();
        params.put("total_amount", "0.01");

        assertThat(paymentService.handleNotify(params)).isEqualTo("failure");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void handleNotify_wait_buyer_pay_returns_success_without_markpaid() {
        when(notifyVerifier.verify(any())).thenReturn(true);
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        Map<String, String> params = validNotifyParams();
        params.put("trade_status", "WAIT_BUYER_PAY");

        assertThat(paymentService.handleNotify(params)).isEqualTo("success");
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void handleNotify_trade_success_marks_paid_and_returns_success() {
        when(notifyVerifier.verify(any())).thenReturn(true);
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        assertThat(paymentService.handleNotify(validNotifyParams())).isEqualTo("success");
        verify(subOrderMapper).update(isNull(), any());
        verify(paymentRecordMapper).update(isNull(), any());
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    // ------------------------------------------------------------------ queryPayResult

    @Test
    void queryPayResult_other_user_rejected_as_not_found() {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        assertThatThrownBy(() -> paymentService.queryPayResult(OTHER_USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void queryPayResult_already_paid_returns_status_without_gateway() {
        OrderEntity order = pendingOrder();
        order.setStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(paymentService.queryPayResult(USER_ID, ORDER_NO)).isEqualTo(1);
        verifyNoInteractions(alipayClient);
    }

    @Test
    void queryPayResult_trade_success_marks_paid_returns_1() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        // isSuccess() 由 subCode 为空推导（SDK 语义），不设即视为成功
        response.setTradeStatus("TRADE_SUCCESS");
        response.setTradeNo(TRADE_NO);
        response.setSendPayDate(new java.util.Date());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        assertThat(paymentService.queryPayResult(USER_ID, ORDER_NO)).isEqualTo(1);
        verify(subOrderMapper).update(isNull(), any());
        verify(zSetOps).remove(OrderService.TIMEOUT_KEY, ORDER_NO);
    }

    @Test
    void queryPayResult_wait_buyer_pay_returns_0_without_markpaid() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setTradeStatus("WAIT_BUYER_PAY");
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        assertThat(paymentService.queryPayResult(USER_ID, ORDER_NO)).isEqualTo(0);
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void queryPayResult_gateway_error_wrapped_as_biz_message() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class)))
                .thenThrow(new AlipayApiException("gateway down"));

        assertThatThrownBy(() -> paymentService.queryPayResult(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("支付服务暂不可用，请稍后重试");
    }
}
