package com.mall.order.address.dto;

/**
 * 收货地址视图（雪花 ID 由全局 SafeLongSerializer 序列化为 JSON 字符串，前端按 string 处理）。
 */
public record AddressView(
        Long id,
        String receiver,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        Integer isDefault) {
}
