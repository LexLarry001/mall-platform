package com.mall.order.refund;

/**
 * 退款域状态常量（M6 扩展子订单 / 主订单状态）。
 *
 * <p>退款单：0 待审核 / 2 已退款 / 3 已驳回 / 4 退款失败（不设持久化"退款中"，审批同步调沙箱）。</p>
 * <p>子订单：在 0-4 基础上新增 5 退款中 / 6 已退款 / 7 部分退款。</p>
 * <p>主订单：在 0-3 基础上新增 4 已退款（全部子订单退款成功）。</p>
 */
public final class RefundStatus {

    private RefundStatus() {
    }

    /** 退款单：待审核。 */
    public static final int PENDING = 0;
    /** 退款单：已退款。 */
    public static final int REFUNDED = 2;
    /** 退款单：已驳回。 */
    public static final int REJECTED = 3;
    /** 退款单：退款失败（可重试）。 */
    public static final int FAILED = 4;

    /** 子订单：退款中（存在待审核/处理中的退款）。 */
    public static final int SUB_REFUNDING = 5;
    /** 子订单：已退款（全部订单项退款成功，终态）。 */
    public static final int SUB_REFUNDED = 6;
    /** 子订单：部分退款（部分订单项退款成功，剩余商品仍有效）。 */
    public static final int SUB_PART_REFUNDED = 7;

    /** 主订单：已退款（全部子订单退款成功）。 */
    public static final int ORDER_REFUNDED = 4;
}
