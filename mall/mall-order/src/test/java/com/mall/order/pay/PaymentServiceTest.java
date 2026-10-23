package com.mall.order.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.PaymentRecordEntity;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.PaymentRecordMapper;
import com.mall.order.mapper.SubOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付下单服务单元测试（mapper / AlipayClient 全 mock，plan Task 2 验收①）。
 *
 * <p>钉死语义：本人守卫（越权"订单不存在"）→ status=0 守卫（"当前状态不可支付"）→
 * 未过期守卫（"订单已超时，请重新下单"）→ payment_record 幂等 upsert（uk 冲突复用）→
 * alipay.trade.page.pay（out_trade_no=订单号、金额快照、subject 约定、notify/return 取自配置）→
 * 表单 HTML 透传；网关异常统一"支付服务暂不可用，请稍后重试"。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long USER_ID = 101L;
    private static final Long OTHER_USER_ID = 202L;
    private static final String ORDER_NO = "M123456789012345678";
    private static final String NOTIFY_URL = "http://gateway.test/api/pay/notify";
    private static final String RETURN_URL = "http://localhost:5173/pay/result";

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
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private PaymentService paymentService;

    /** lambdaQuery 纯 mock 单测需实体 TableInfo 缓存（同 OrderServiceTest 先例）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderEntity.class);
        TableInfoHelper.initTableInfo(assistant, PaymentRecordEntity.class);
    }

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(orderMapper, paymentRecordMapper, subOrderMapper,
                alipayClient, notifyVerifier, redis, "app-id-test", NOTIFY_URL, RETURN_URL, "木集商城订单-");
    }

    /** 待支付、未过期、本人订单。 */
    private OrderEntity pendingOrder() {
        OrderEntity order = new OrderEntity();
        order.setId(9001L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setPayAmount(new BigDecimal("60.00"));
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private AlipayTradePagePayResponse formResponse(String body) {
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse();
        response.setBody(body);
        return response;
    }

    @Test
    void createPayForm_order_not_found() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> paymentService.createPayForm(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void createPayForm_order_of_other_user_rejected_as_not_found() {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());

        assertThatThrownBy(() -> paymentService.createPayForm(OTHER_USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单不存在");
    }

    @Test
    void createPayForm_status_not_payable() {
        OrderEntity order = pendingOrder();
        order.setStatus(3);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> paymentService.createPayForm(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("当前状态不可支付");
    }

    @Test
    void createPayForm_expired_order_rejected() {
        OrderEntity order = pendingOrder();
        order.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThatThrownBy(() -> paymentService.createPayForm(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("订单已超时，请重新下单");
    }

    @Test
    void createPayForm_success_inserts_record_and_returns_form() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(paymentRecordMapper.selectOne(any())).thenReturn(null);
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class)))
                .thenReturn(formResponse("<form id=\"alipayForm\">pay</form>"));

        String form = paymentService.createPayForm(USER_ID, ORDER_NO);

        assertThat(form).isEqualTo("<form id=\"alipayForm\">pay</form>");

        // 流水快照：order_no + amount=pay_amount + status=0
        ArgumentCaptor<PaymentRecordEntity> recordCaptor = ArgumentCaptor.forClass(PaymentRecordEntity.class);
        verify(paymentRecordMapper).insert(recordCaptor.capture());
        PaymentRecordEntity record = recordCaptor.getValue();
        assertThat(record.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(record.getAmount()).isEqualByComparingTo("60.00");
        assertThat(record.getStatus()).isZero();

        // 网关请求：out_trade_no=订单号、金额快照、subject 约定、notify/return 取自配置
        ArgumentCaptor<AlipayTradePagePayRequest> reqCaptor =
                ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(alipayClient).pageExecute(reqCaptor.capture());
        AlipayTradePagePayRequest request = reqCaptor.getValue();
        assertThat(request.getNotifyUrl()).isEqualTo(NOTIFY_URL);
        assertThat(request.getReturnUrl()).isEqualTo(RETURN_URL);
        AlipayTradePagePayModel model = (AlipayTradePagePayModel) request.getBizModel();
        assertThat(model.getOutTradeNo()).isEqualTo(ORDER_NO);
        assertThat(model.getTotalAmount()).isEqualTo("60.00");
        assertThat(model.getSubject()).isEqualTo("木集商城订单-" + ORDER_NO);
        assertThat(model.getProductCode()).isEqualTo("FAST_INSTANT_TRADE_PAY");
    }

    /**
     * 回归：环境变量 MALL_ALIPAY_RETURN_URL 被设为空串时会覆盖 yml 默认值，
     * 导致支付表单 return_url 为空、支付宝支付后无法同步回跳。构造器需兜底默认地址。
     */
    @Test
    void createPayForm_blank_return_url_falls_back_to_default() throws Exception {
        PaymentService serviceWithBlankReturnUrl = new PaymentService(orderMapper, paymentRecordMapper,
                subOrderMapper, alipayClient, notifyVerifier, redis, "app-id-test", NOTIFY_URL, "  ", "木集商城订单-");
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(paymentRecordMapper.selectOne(any())).thenReturn(new PaymentRecordEntity());
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class)))
                .thenReturn(formResponse("<form>pay</form>"));

        serviceWithBlankReturnUrl.createPayForm(USER_ID, ORDER_NO);

        ArgumentCaptor<AlipayTradePagePayRequest> reqCaptor =
                ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(alipayClient).pageExecute(reqCaptor.capture());
        assertThat(reqCaptor.getValue().getReturnUrl()).isEqualTo(RETURN_URL);
    }

    @Test
    void createPayForm_existing_record_reused_without_insert() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(paymentRecordMapper.selectOne(any())).thenReturn(new PaymentRecordEntity());
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class)))
                .thenReturn(formResponse("<form>pay</form>"));

        String form = paymentService.createPayForm(USER_ID, ORDER_NO);

        assertThat(form).isEqualTo("<form>pay</form>");
        verify(paymentRecordMapper, never()).insert(any(PaymentRecordEntity.class));
    }

    @Test
    void createPayForm_duplicate_key_means_concurrent_insert_reused() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(paymentRecordMapper.selectOne(any())).thenReturn(null);
        when(paymentRecordMapper.insert(any(PaymentRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_order_no"));
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class)))
                .thenReturn(formResponse("<form>pay</form>"));

        String form = paymentService.createPayForm(USER_ID, ORDER_NO);

        assertThat(form).isEqualTo("<form>pay</form>");
    }

    @Test
    void createPayForm_gateway_error_wrapped_as_biz_message() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(pendingOrder());
        when(paymentRecordMapper.selectOne(any())).thenReturn(null);
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class)))
                .thenThrow(new AlipayApiException("gateway down"));

        assertThatThrownBy(() -> paymentService.createPayForm(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessage("支付服务暂不可用，请稍后重试");
    }
}
