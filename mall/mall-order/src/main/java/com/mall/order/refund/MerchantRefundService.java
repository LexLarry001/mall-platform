package com.mall.order.refund;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BizException;
import com.mall.order.entity.OrderItemEntity;
import com.mall.order.entity.PaymentRecordEntity;
import com.mall.order.entity.RefundOrderEntity;
import com.mall.order.entity.ShipmentEntity;
import com.mall.order.entity.SubOrderEntity;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.PaymentRecordMapper;
import com.mall.order.mapper.RefundOrderMapper;
import com.mall.order.mapper.ShipmentMapper;
import com.mall.order.mapper.SubOrderMapper;
import com.mall.order.order.OrderAggregationService;
import com.mall.order.refund.dto.RefundView;
import com.mall.product.mapper.ProductSkuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 商家退款审核（M6 Task 3）：列表 / 驳回 / 同意（支付宝沙箱退款）/ 失败重试。
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>权限：退款单 shop_id 必须匹配登录商家，越权统一"退款单不存在"。</li>
 *   <li>同意：条件更新 0→2/4，天然幂等；沙箱失败落库后再抛业务错误（事务拆分，保证失败记录不被回滚）。</li>
 *   <li>成功：未发货才回补库存；子订单按已退款金额判定 6 已退款 / 7 部分退款；主订单聚合。</li>
 *   <li>驳回/失败：若该子订单仍有其它待审核退款则保持 5，否则恢复申请前状态（sub_status_before）。</li>
 * </ul>
 */
@Service
public class MerchantRefundService {

    private final RefundOrderMapper refundOrderMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShipmentMapper shipmentMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final ProductSkuMapper skuMapper;
    private final OrderAggregationService aggregationService;
    private final RefundService refundService;
    private final AlipayClient alipayClient;
    private final TransactionTemplate tx;

    public MerchantRefundService(RefundOrderMapper refundOrderMapper, SubOrderMapper subOrderMapper,
                                 OrderItemMapper orderItemMapper, ShipmentMapper shipmentMapper,
                                 PaymentRecordMapper paymentRecordMapper, ProductSkuMapper skuMapper,
                                 OrderAggregationService aggregationService, RefundService refundService,
                                 AlipayClient alipayClient, PlatformTransactionManager transactionManager) {
        this.refundOrderMapper = refundOrderMapper;
        this.subOrderMapper = subOrderMapper;
        this.orderItemMapper = orderItemMapper;
        this.shipmentMapper = shipmentMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.skuMapper = skuMapper;
        this.aggregationService = aggregationService;
        this.refundService = refundService;
        this.alipayClient = alipayClient;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 商家售后列表（可按状态过滤，新在前）。 */
    public List<RefundView> list(Long shopId, Integer status) {
        return refundOrderMapper.selectList(Wrappers.<RefundOrderEntity>lambdaQuery()
                        .eq(RefundOrderEntity::getShopId, shopId)
                        .eq(status != null, RefundOrderEntity::getStatus, status)
                        .orderByDesc(RefundOrderEntity::getId))
                .stream().map(refundService::toView).toList();
    }

    /** 商家售后详情。越权/不存在统一"退款单不存在"。 */
    public RefundView detail(Long shopId, String refundNo) {
        return refundService.toView(loadOwned(shopId, refundNo));
    }

    /**
     * 驳回：仅待审核 → 3 已驳回；恢复子订单状态（无其它待审核时）→ 主订单聚合。
     */
    public void reject(Long shopId, String refundNo, String remark) {
        RefundOrderEntity refund = loadOwned(shopId, refundNo);
        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new BizException("当前状态不可审核");
        }
        tx.executeWithoutResult(status -> {
            int rows = refundOrderMapper.update(null, Wrappers.<RefundOrderEntity>lambdaUpdate()
                    .set(RefundOrderEntity::getStatus, RefundStatus.REJECTED)
                    .set(RefundOrderEntity::getAuditRemark, trim(remark, 255))
                    .set(RefundOrderEntity::getAuditTime, LocalDateTime.now())
                    .eq(RefundOrderEntity::getId, refund.getId())
                    .eq(RefundOrderEntity::getStatus, RefundStatus.PENDING));
            if (rows == 0) {
                throw new BizException("退款单状态已变更，请刷新");
            }
            restoreSubOrderStatus(refund);
            aggregationService.aggregate(refund.getOrderId());
        });
    }

    /**
     * 同意退款：同步调用支付宝沙箱退款。
     * 成功 → 2 已退款 + 库存回补（未发货）+ 子订单 6/7 + 主订单聚合；
     * 失败 → 4 退款失败（落错误码）→ 恢复子订单 → 抛"退款失败，可稍后重试"。
     */
    public void approve(Long shopId, String refundNo, String remark) {
        RefundOrderEntity refund = loadOwned(shopId, refundNo);
        if (refund.getStatus() != RefundStatus.PENDING) {
            throw new BizException("当前状态不可审核");
        }
        RefundCall call = callAlipay(refund);
        if (call.success()) {
            settleSuccess(refund, remark, call.tradeNo(), RefundStatus.PENDING);
        } else {
            markFailed(refund, remark, call, RefundStatus.PENDING);
            throw new BizException("退款失败，可稍后重试");
        }
    }

    /** 失败重试：仅 status=4，同一 refund_no 重放（支付宝 out_request_no 幂等）。 */
    public void retry(Long shopId, String refundNo) {
        RefundOrderEntity refund = loadOwned(shopId, refundNo);
        if (refund.getStatus() != RefundStatus.FAILED) {
            throw new BizException("当前状态不可重试");
        }
        RefundCall call = callAlipay(refund);
        if (call.success()) {
            settleSuccess(refund, refund.getAuditRemark(), call.tradeNo(), RefundStatus.FAILED);
        } else {
            markFailed(refund, refund.getAuditRemark(), call, RefundStatus.FAILED);
            throw new BizException("退款失败，可稍后重试");
        }
    }

    // ------------------------------------------------------------------ 内核

    private RefundOrderEntity loadOwned(Long shopId, String refundNo) {
        RefundOrderEntity refund = refundOrderMapper.selectOne(Wrappers.<RefundOrderEntity>lambdaQuery()
                .eq(RefundOrderEntity::getRefundNo, refundNo));
        if (refund == null || shopId == null || !shopId.equals(refund.getShopId())) {
            throw new BizException("退款单不存在");
        }
        return refund;
    }

    /** 退款成功：条件更新 → 库存回补 → 子订单 6/7 → 主订单聚合（同一事务）。 */
    private void settleSuccess(RefundOrderEntity refund, String remark, String tradeNo, int expectedStatus) {
        tx.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            int rows = refundOrderMapper.update(null, Wrappers.<RefundOrderEntity>lambdaUpdate()
                    .set(RefundOrderEntity::getStatus, RefundStatus.REFUNDED)
                    .set(RefundOrderEntity::getAuditRemark, trim(remark, 255))
                    .set(RefundOrderEntity::getAuditTime, now)
                    .set(RefundOrderEntity::getRefundTime, now)
                    .set(RefundOrderEntity::getAlipayTradeNo, trim(tradeNo, 64))
                    .set(RefundOrderEntity::getFailCode, null)
                    .set(RefundOrderEntity::getFailMsg, null)
                    .eq(RefundOrderEntity::getId, refund.getId())
                    .eq(RefundOrderEntity::getStatus, expectedStatus));
            if (rows == 0) {
                throw new BizException("退款单状态已变更，请刷新");
            }

            restoreStockIfUnshipped(refund);
            refreshSubOrderAfterRefund(refund);
            aggregationService.aggregate(refund.getOrderId());
        });
    }

    /** 退款失败：落错误码并恢复子订单状态（事务提交后再抛出业务错误）。 */
    private void markFailed(RefundOrderEntity refund, String remark, RefundCall call, int expectedStatus) {
        tx.executeWithoutResult(status -> {
            int rows = refundOrderMapper.update(null, Wrappers.<RefundOrderEntity>lambdaUpdate()
                    .set(RefundOrderEntity::getStatus, RefundStatus.FAILED)
                    .set(RefundOrderEntity::getAuditRemark, trim(remark, 255))
                    .set(RefundOrderEntity::getAuditTime, LocalDateTime.now())
                    .set(RefundOrderEntity::getFailCode, trim(call.failCode(), 64))
                    .set(RefundOrderEntity::getFailMsg, trim(call.failMsg(), 255))
                    .eq(RefundOrderEntity::getId, refund.getId())
                    .eq(RefundOrderEntity::getStatus, expectedStatus));
            if (rows == 0) {
                throw new BizException("退款单状态已变更，请刷新");
            }
            restoreSubOrderStatus(refund);
        });
    }

    /** 未发货（无 shipment）才回补库存；已发货货在买家手上，不回补。 */
    private void restoreStockIfUnshipped(RefundOrderEntity refund) {
        long shipped = shipmentMapper.selectCount(Wrappers.<ShipmentEntity>lambdaQuery()
                .eq(ShipmentEntity::getSubOrderId, refund.getSubOrderId()));
        if (shipped == 0) {
            skuMapper.restoreStock(refund.getSkuId(), refund.getQuantity());
        }
    }

    /**
     * 退款成功后重算子订单状态：已退款金额 ≥ 子订单金额 → 6 已退款，否则 → 7 部分退款。
     * 条件更新仅从 5 退款中推进，避免与并发审核冲突。
     */
    private void refreshSubOrderAfterRefund(RefundOrderEntity refund) {
        SubOrderEntity sub = subOrderMapper.selectById(refund.getSubOrderId());
        if (sub == null) {
            throw new BizException("订单不存在");
        }
        List<OrderItemEntity> items = orderItemMapper.selectList(Wrappers.<OrderItemEntity>lambdaQuery()
                .eq(OrderItemEntity::getSubOrderId, sub.getId()));
        BigDecimal itemSum = items.stream()
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refundedSum = refundOrderMapper.selectList(Wrappers.<RefundOrderEntity>lambdaQuery()
                        .eq(RefundOrderEntity::getSubOrderId, sub.getId())
                        .eq(RefundOrderEntity::getStatus, RefundStatus.REFUNDED))
                .stream().map(RefundOrderEntity::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int target = refundedSum.compareTo(itemSum) >= 0
                ? RefundStatus.SUB_REFUNDED : RefundStatus.SUB_PART_REFUNDED;
        subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, target)
                .eq(SubOrderEntity::getId, sub.getId())
                // 重试场景：失败时子订单已恢复为 1/2/7，仍需推进到 6/7
                .in(SubOrderEntity::getStatus, 1, 2,
                        RefundStatus.SUB_REFUNDING, RefundStatus.SUB_PART_REFUNDED));
    }

    /** 驳回/失败后恢复子订单：仍有其它待审核退款 → 保持 5；否则回 sub_status_before。 */
    private void restoreSubOrderStatus(RefundOrderEntity refund) {
        long otherPending = refundOrderMapper.selectCount(Wrappers.<RefundOrderEntity>lambdaQuery()
                .eq(RefundOrderEntity::getSubOrderId, refund.getSubOrderId())
                .eq(RefundOrderEntity::getStatus, RefundStatus.PENDING)
                .ne(RefundOrderEntity::getId, refund.getId()));
        if (otherPending > 0) {
            return;
        }
        subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, refund.getSubStatusBefore())
                .eq(SubOrderEntity::getId, refund.getSubOrderId())
                .eq(SubOrderEntity::getStatus, RefundStatus.SUB_REFUNDING));
    }

    /** 调用支付宝退款（沙箱）；所有异常转失败结果，不抛 AlipayApiException。 */
    private RefundCall callAlipay(RefundOrderEntity refund) {
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(refund.getOrderNo());
        PaymentRecordEntity pay = paymentRecordMapper.selectOne(Wrappers.<PaymentRecordEntity>lambdaQuery()
                .eq(PaymentRecordEntity::getOrderNo, refund.getOrderNo()));
        if (pay != null && Objects.nonNull(pay.getTradeNo())) {
            model.setTradeNo(pay.getTradeNo());
        }
        model.setRefundAmount(refund.getRefundAmount().toPlainString());
        model.setOutRequestNo(refund.getRefundNo());
        model.setRefundReason(trim(refund.getReason(), 255));
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        request.setBizModel(model);
        try {
            AlipayTradeRefundResponse response = alipayClient.execute(request);
            if (response.isSuccess()) {
                return new RefundCall(true, response.getTradeNo(), null, null);
            }
            String code = response.getSubCode() != null ? response.getSubCode() : response.getCode();
            String msg = response.getSubMsg() != null ? response.getSubMsg() : response.getMsg();
            return new RefundCall(false, null, code, msg);
        } catch (AlipayApiException e) {
            return new RefundCall(false, null, "ALIPAY_EXCEPTION", e.getMessage());
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 沙箱退款调用结果。 */
    private record RefundCall(boolean success, String tradeNo, String failCode, String failMsg) {
    }
}
