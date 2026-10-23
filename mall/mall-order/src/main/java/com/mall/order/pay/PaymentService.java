package com.mall.order.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderEntity;
import com.mall.order.entity.PaymentRecordEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderMapper;
import com.mall.order.mapper.PaymentRecordMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.OrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 支付域服务（plan Task 2 支付下单 + Task 3 回调/查询兜底）。
 *
 * <p>下单不变量：本人/状态/过期三重守卫；流水幂等 upsert（uk_order_no 冲突复用）；
 * 金额以库为准；网关异常统一业务文案。</p>
 *
 * <p>支付成功内核 markPaid 复刻 {@code OrderService.doCancel} 模式：主订单条件更新 0→1
 * （行数 0 即幂等返回）→ 子订单 0→1（待发货）→ 流水回填 → ZSet 清锚点。
 * notify 与 query 两条路径共用，条件更新天然抗并发；已终态订单的迟到成功回调仅忽略。</p>
 */
@Service
public class PaymentService {

    /** 支付宝交易状态：支付成功。 */
    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    /** 支付宝交易状态：交易结束（同视为支付成功）。 */
    private static final String TRADE_FINISHED = "TRADE_FINISHED";
    /** 支付宝时间格式（gmt_payment / send_pay_date）。 */
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 同步回跳兜底地址：环境变量被显式设为空串时仍能回到买家端支付结果页。 */
    private static final String DEFAULT_RETURN_URL = "http://localhost:5173/pay/result";

    private final OrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final SubOrderMapper subOrderMapper;
    private final AlipayClient alipayClient;
    private final AlipayNotifyVerifier notifyVerifier;
    private final StringRedisTemplate redis;
    private final String appId;
    private final String notifyUrl;
    private final String returnUrl;
    private final String subjectPrefix;

    public PaymentService(OrderMapper orderMapper, PaymentRecordMapper paymentRecordMapper,
                          SubOrderMapper subOrderMapper, AlipayClient alipayClient,
                          AlipayNotifyVerifier notifyVerifier, StringRedisTemplate redis,
                          @Value("${mall.alipay.app-id:}") String appId,
                          @Value("${mall.alipay.notify-url:}") String notifyUrl,
                          @Value("${mall.alipay.return-url:}") String returnUrl,
                          @Value("${mall.alipay.subject-prefix:木集商城订单-}") String subjectPrefix) {
        this.orderMapper = orderMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.subOrderMapper = subOrderMapper;
        this.alipayClient = alipayClient;
        this.notifyVerifier = notifyVerifier;
        this.redis = redis;
        this.appId = appId;
        this.notifyUrl = notifyUrl;
        // 环境变量存在但为空串时 Spring 会覆盖 yml 默认值，这里再做一次兜底
        this.returnUrl = returnUrl == null || returnUrl.isBlank() ? DEFAULT_RETURN_URL : returnUrl.trim();
        this.subjectPrefix = subjectPrefix;
    }

    /**
     * 发起支付：守卫校验 → 流水幂等 upsert → alipay.trade.page.pay → 返回表单 HTML。
     * 同一订单未支付前可重复调用（支付宝对同一 out_trade_no 幂等，重新唤起收银台）。
     */
    public String createPayForm(Long userId, String orderNo) {
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException("订单不存在");
        }
        if (order.getStatus() != 0) {
            throw new BizException("当前状态不可支付");
        }
        if (order.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException("订单已超时，请重新下单");
        }

        ensurePaymentRecord(order);

        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderNo);
        model.setTotalAmount(order.getPayAmount().toPlainString());
        model.setSubject(subjectPrefix + orderNo);
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(notifyUrl);
        request.setReturnUrl(returnUrl);
        request.setBizModel(model);
        try {
            return alipayClient.pageExecute(request).getBody();
        } catch (AlipayApiException e) {
            throw new BizException("支付服务暂不可用，请稍后重试");
        }
    }

    /**
     * 支付成功内核（notify / query 共用，幂等）：
     * 主订单条件更新 0→1（行数 0 即已并发处理或订单已终态，直接返回 false）→
     * 子订单 0→1（待发货）→ 流水回填（trade_no/notify_raw/pay_time）→ ZSet 清超时锚点。
     *
     * @return 本次是否实际推进了支付成功
     */
    @Transactional
    public boolean markPaid(String orderNo, String tradeNo, String rawBody, LocalDateTime payTime) {
        int rows = orderMapper.update(null, Wrappers.<OrderEntity>lambdaUpdate()
                .set(OrderEntity::getStatus, 1)
                .eq(OrderEntity::getOrderNo, orderNo)
                .eq(OrderEntity::getStatus, 0));
        if (rows == 0) {
            return false;
        }
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, 1)
                .eq(SubOrderEntity::getOrderId, order.getId())
                .eq(SubOrderEntity::getStatus, 0));
        paymentRecordMapper.update(null, Wrappers.<PaymentRecordEntity>lambdaUpdate()
                .set(PaymentRecordEntity::getStatus, 1)
                .set(PaymentRecordEntity::getTradeNo, tradeNo)
                .set(PaymentRecordEntity::getNotifyRaw, rawBody)
                .set(PaymentRecordEntity::getPayTime, payTime)
                .eq(PaymentRecordEntity::getOrderNo, orderNo)
                .eq(PaymentRecordEntity::getStatus, 0));
        redis.opsForZSet().remove(OrderService.TIMEOUT_KEY, orderNo);
        return true;
    }

    /**
     * 异步回调处理（支付宝规范：应答纯文本 success/failure）：
     * 验签 → 校验 app_id / out_trade_no 存在 / total_amount 与订单一致 →
     * trade_status∈(TRADE_SUCCESS, TRADE_FINISHED) → markPaid → "success"。
     * 验签失败、金额/APPID 不符、订单不存在一律 "failure"；
     * 非成功状态（如 WAIT_BUYER_PAY）确认收到但不处理，返 "success" 阻断支付宝重发。
     */
    public String handleNotify(Map<String, String> params) {
        if (!notifyVerifier.verify(params)) {
            return "failure";
        }
        String orderNo = params.get("out_trade_no");
        String totalAmount = params.get("total_amount");
        if (orderNo == null || totalAmount == null || !appId.equals(params.get("app_id"))) {
            return "failure";
        }
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null) {
            return "failure";
        }
        BigDecimal notifyAmount;
        try {
            notifyAmount = new BigDecimal(totalAmount);
        } catch (NumberFormatException e) {
            return "failure";
        }
        if (order.getPayAmount().compareTo(notifyAmount) != 0) {
            return "failure";
        }
        String tradeStatus = params.get("trade_status");
        if (!TRADE_SUCCESS.equals(tradeStatus) && !TRADE_FINISHED.equals(tradeStatus)) {
            return "success";
        }
        markPaid(orderNo, params.get("trade_no"), params.toString(),
                parseAlipayTime(params.get("gmt_payment")));
        return "success";
    }

    /**
     * 主动查询支付结果（本地无公网收不到异步回调时的兜底推进）：
     * 本人守卫 → 已推进过直接返回当前 status → alipay.trade.query →
     * TRADE_SUCCESS/FINISHED 则 markPaid → 返回订单当前 status。
     */
    public Integer queryPayResult(Long userId, String orderNo) {
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException("订单不存在");
        }
        if (order.getStatus() != 0) {
            return order.getStatus();
        }
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizModel(buildQueryModel(orderNo));
        AlipayTradeQueryResponse response;
        try {
            response = alipayClient.execute(request);
        } catch (AlipayApiException e) {
            throw new BizException("支付服务暂不可用，请稍后重试");
        }
        if (response.isSuccess() && (TRADE_SUCCESS.equals(response.getTradeStatus())
                || TRADE_FINISHED.equals(response.getTradeStatus()))) {
            markPaid(orderNo, response.getTradeNo(), response.getBody(),
                    parseAlipayTime(response.getSendPayDate()));
            return 1;
        }
        return order.getStatus();
    }

    private com.alipay.api.domain.AlipayTradeQueryModel buildQueryModel(String orderNo) {
        com.alipay.api.domain.AlipayTradeQueryModel model =
                new com.alipay.api.domain.AlipayTradeQueryModel();
        model.setOutTradeNo(orderNo);
        return model;
    }

    /** 查询响应的 send_pay_date 是 Date：空值兜底 now（pay_time 仅为排查依据）。 */
    private LocalDateTime parseAlipayTime(java.util.Date date) {
        return date == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
    }

    /** 回调报文的 gmt_payment 是字符串：空串/格式异常兜底 now（不影响状态机）。 */
    private LocalDateTime parseAlipayTime(String text) {
        if (text == null || text.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(text, ALIPAY_TIME);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    /** 流水幂等 upsert：已存在直接复用；插入撞 uk_order_no（并发首发）同样复用。 */
    private void ensurePaymentRecord(OrderEntity order) {
        PaymentRecordEntity existing = paymentRecordMapper.selectOne(
                Wrappers.<PaymentRecordEntity>lambdaQuery()
                        .eq(PaymentRecordEntity::getOrderNo, order.getOrderNo()));
        if (existing != null) {
            return;
        }
        PaymentRecordEntity record = new PaymentRecordEntity();
        record.setOrderNo(order.getOrderNo());
        record.setAmount(order.getPayAmount());
        record.setStatus(0);
        try {
            paymentRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发重复发起支付撞唯一键：复用已存在流水，天然幂等
        }
    }
}
