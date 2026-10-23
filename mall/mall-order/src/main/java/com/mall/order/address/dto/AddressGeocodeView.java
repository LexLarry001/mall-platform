package com.mall.order.address.dto;

/**
 * 地址高德识别结果（仅校验，不落库）。
 *
 * @param recognized       是否识别成功
 * @param lng              经度（未识别为 null）
 * @param lat              纬度（未识别为 null）
 * @param formattedAddress 高德标准化地址
 * @param level            高德匹配级别（省/市/区县/门牌等）
 * @param message          未识别时的原因提示
 */
public record AddressGeocodeView(boolean recognized, Double lng, Double lat,
                                 String formattedAddress, String level, String message) {
}
