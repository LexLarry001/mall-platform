package com.mall.order.refund;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 买家退款申请与查询（M6 Task 2）。
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>主订单 / 子订单 / 订单项三级归属校验，越权统一"订单不存在"。</li>
 *   <li>仅子订单 status ∈ {1 待发货, 2 待收货, 7 部分退款} 可申请；申请后子订单 → 5 退款中。</li>
 *   <li>金额以订单项快照为准（price × quantity），整项退款，不信任前端金额。</li>
 *   <li>同一订单项已有待审核/已退款退款单时拒绝重复申请；驳回/失败后可重新申请。</li>
 * </ul>
 */
@Service
public class RefundService {

    /** 退款单号前缀（= 支付宝 out_request_no）。 */
    public static final String REFUND_NO_PREFIX = "RF";

    /** 凭证图上限。 */
    private static final int MAX_EVIDENCE = 3;

    private final RefundOrderMapper refundOrderMapper;
    private final OrderMapper orderMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ObjectMapper objectMapper;

    public RefundService(RefundOrderMapper refundOrderMapper, OrderMapper orderMapper,
                         SubOrderMapper subOrderMapper, OrderItemMapper orderItemMapper,
                         ObjectMapper objectMapper) {
        this.refundOrderMapper = refundOrderMapper;
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.orderItemMapper = orderItemMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 买家申请退款：校验归属/状态/重复 → 写退款单（待审核）→ 子订单 → 5 退款中。
     *
     * @return 退款单号
     */
    @Transactional
    public String apply(Long userId, RefundApplyRequest request) {
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("请填写退款原因");
        }
        if (reason.length() > 255) {
            throw new BizException("退款原因过长");
        }
        List<String> evidence = request.evidenceImages() == null ? List.of()
                : request.evidenceImages().stream().filter(url -> url != null && !url.isBlank()).toList();
        if (evidence.size() > MAX_EVIDENCE) {
            throw new BizException("凭证图片最多 3 张");
        }

        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, request.orderNo()));
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BizException("订单不存在");
        }
        SubOrderEntity sub = subOrderMapper.selectOne(Wrappers.<SubOrderEntity>lambdaQuery()
                .eq(SubOrderEntity::getSubOrderNo, request.subOrderNo()));
        if (sub == null || !order.getId().equals(sub.getOrderId())) {
            throw new BizException("订单不存在");
        }
        int subStatus = sub.getStatus();
        if (subStatus != 1 && subStatus != 2 && subStatus != 7) {
            throw new BizException("当前状态不可申请退款");
        }
        OrderItemEntity item = request.orderItemId() == null ? null
                : orderItemMapper.selectById(request.orderItemId());
        if (item == null || !sub.getId().equals(item.getSubOrderId())) {
            throw new BizException("订单不存在");
        }
        ensureNoActiveRefund(item.getId());

        BigDecimal amount = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("退款金额异常");
        }

        RefundOrderEntity refund = new RefundOrderEntity();
        refund.setRefundNo(REFUND_NO_PREFIX + IdWorker.getId());
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setSubOrderId(sub.getId());
        refund.setSubOrderNo(sub.getSubOrderNo());
        refund.setOrderItemId(item.getId());
        refund.setSpuId(item.getSpuId());
        refund.setSkuId(item.getSkuId());
        refund.setProductName(item.getProductName());
        refund.setShopId(sub.getShopId());
        refund.setUserId(userId);
        refund.setQuantity(item.getQuantity());
        refund.setRefundAmount(amount);
        refund.setReason(reason);
        refund.setEvidenceImages(toJson(evidence));
        refund.setStatus(RefundStatus.PENDING);
        refund.setSubStatusBefore(subStatus);
        refundOrderMapper.insert(refund);

        // 子订单 → 5 退款中（条件更新防并发：另一笔退款已置 5 时仍允许，故不用 status=subStatus 限定）
        int rows = subOrderMapper.update(null, Wrappers.<SubOrderEntity>lambdaUpdate()
                .set(SubOrderEntity::getStatus, RefundStatus.SUB_REFUNDING)
                .eq(SubOrderEntity::getId, sub.getId())
                .in(SubOrderEntity::getStatus, 1, 2, 7));
        if (rows == 0) {
            throw new BizException("当前状态不可申请退款");
        }
        return refund.getRefundNo();
    }

    /** 买家：某主订单下的全部退款单（新在前）。越权统一"订单不存在"。 */
    public List<RefundView> listByOrder(Long userId, String orderNo) {
        OrderEntity order = orderMapper.selectOne(Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BizException("订单不存在");
        }
        return refundOrderMapper.selectList(Wrappers.<RefundOrderEntity>lambdaQuery()
                        .eq(RefundOrderEntity::getOrderId, order.getId())
                        .orderByDesc(RefundOrderEntity::getId))
                .stream().map(this::toView).toList();
    }

    /** 买家：退款单详情（仅本人）。越权统一"退款单不存在"。 */
    public RefundView detail(Long userId, String refundNo) {
        RefundOrderEntity refund = refundOrderMapper.selectOne(Wrappers.<RefundOrderEntity>lambdaQuery()
                .eq(RefundOrderEntity::getRefundNo, refundNo));
        if (refund == null || !userId.equals(refund.getUserId())) {
            throw new BizException("退款单不存在");
        }
        return toView(refund);
    }

    /** 同一订单项不允许同时存在待审核或已退款的退款单。 */
    private void ensureNoActiveRefund(Long orderItemId) {
        List<RefundOrderEntity> existing = refundOrderMapper.selectList(
                Wrappers.<RefundOrderEntity>lambdaQuery()
                        .eq(RefundOrderEntity::getOrderItemId, orderItemId)
                        .in(RefundOrderEntity::getStatus, RefundStatus.PENDING, RefundStatus.REFUNDED));
        if (existing.isEmpty()) {
            return;
        }
        boolean refunded = existing.stream().anyMatch(r -> r.getStatus() == RefundStatus.REFUNDED);
        throw new BizException(refunded ? "该商品已退款" : "该商品已申请退款");
    }

    /** 退款单 → 视图（凭证 JSON 解析失败按空列表兜底，不影响主流程）。 */
    public RefundView toView(RefundOrderEntity entity) {
        return new RefundView(entity.getRefundNo(), entity.getOrderNo(), entity.getSubOrderNo(),
                entity.getOrderItemId(), entity.getSpuId(), entity.getSkuId(), entity.getProductName(),
                entity.getShopId(), entity.getQuantity(), entity.getRefundAmount(), entity.getReason(),
                fromJson(entity.getEvidenceImages()), entity.getStatus(), entity.getAuditRemark(),
                entity.getAuditTime(), entity.getRefundTime(), entity.getFailMsg(), entity.getCreateTime());
    }

    private String toJson(List<String> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (JsonProcessingException e) {
            throw new BizException("凭证格式不正确");
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
