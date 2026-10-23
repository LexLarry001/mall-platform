package com.mall.order.logistics;

/**
 * 物流状态常量（手动模式 + 高德模式通用；高德接入后 AMAP 的状态码映射到这些值）。
 *
 * <p>商家发货后：SHIPPED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED（终态）
 * EXCEPTION 为后续扩展的异常态（本期不触发）。</p>
 */
public final class LogisticsStatus {

    /** 刚创建（ManualProvider 内部占位，不落库）。 */
    public static final String INIT = "INIT";
    /** 已发货：商家点发货按钮后立即进入。 */
    public static final String SHIPPED = "SHIPPED";
    /** 运输中：商家手动推进。 */
    public static final String IN_TRANSIT = "IN_TRANSIT";
    /** 派送中：商家手动推进。 */
    public static final String OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    /** 已签收：商家手动推进到终态（买家确认收货不自动推到此状态，两者独立）。 */
    public static final String DELIVERED = "DELIVERED";
    /** 异常：后续扩展。 */
    public static final String EXCEPTION = "EXCEPTION";

    /** 状态流转顺序（商家按钮显示下一个状态）。 */
    private static final String[] FLOW = {SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED};

    /** 返回当前状态的下一个状态；终态 DELIVERED 返回 null。 */
    public static String next(String current) {
        for (int i = 0; i < FLOW.length - 1; i++) {
            if (FLOW[i].equals(current)) {
                return FLOW[i + 1];
            }
        }
        return null;
    }

    /** 中文展示名（前端/日志用）。 */
    public static String chinese(String status) {
        return switch (status) {
            case SHIPPED -> "已发货";
            case IN_TRANSIT -> "运输中";
            case OUT_FOR_DELIVERY -> "派送中";
            case DELIVERED -> "已签收";
            case EXCEPTION -> "异常";
            default -> status;
        };
    }

    private LogisticsStatus() {
    }
}
