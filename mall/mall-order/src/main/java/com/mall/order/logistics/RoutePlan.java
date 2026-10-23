package com.mall.order.logistics;

import java.util.List;

/**
 * 配送路线规划结果（高德驾车路径规划）。
 *
 * <p>{@code available=false} 表示高德不可用（无 Key / 地理编码失败 / 网络超时），
 * 调用方应降级为手动物流，发货流程不阻断。</p>
 *
 * @param fromLng          起点经度
 * @param fromLat          起点纬度
 * @param toLng            终点经度
 * @param toLat            终点纬度
 * @param polyline         折线坐标数组（每项 [lng, lat]），仅存 MongoDB
 * @param distanceMeters   全程距离（米）
 * @param durationSeconds  预计耗时（秒）
 * @param fromAddress      发货地址原文
 * @param toAddress        收货地址原文
 * @param available        路线是否可用
 */
public record RoutePlan(
        double fromLng,
        double fromLat,
        double toLng,
        double toLat,
        List<double[]> polyline,
        int distanceMeters,
        int durationSeconds,
        String fromAddress,
        String toAddress,
        boolean available) {

    public RoutePlan {
        polyline = polyline == null ? List.of() : polyline;
    }

    /** 路线不可用（降级手动物流）。 */
    public static RoutePlan unavailable(String fromAddress, String toAddress) {
        return new RoutePlan(0, 0, 0, 0, List.of(), 0, 0, fromAddress, toAddress, false);
    }
}
