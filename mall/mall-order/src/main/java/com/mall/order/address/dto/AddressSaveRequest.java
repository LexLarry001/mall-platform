package com.mall.order.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 收货地址新增/编辑请求。
 *
 * @param isDefault {@code true} 时设为本买家默认地址（服务端同事务清旧默认）；缺省/其他按非默认处理
 */
public record AddressSaveRequest(
        @NotBlank(message = "收货人不能为空") @Size(max = 32, message = "收货人过长") String receiver,
        @NotBlank(message = "手机号不能为空") @Size(max = 20, message = "手机号过长") String phone,
        @NotBlank(message = "省不能为空") String province,
        @NotBlank(message = "市不能为空") String city,
        @NotBlank(message = "区/县不能为空") String district,
        @NotBlank(message = "详细地址不能为空") @Size(max = 255, message = "详细地址过长") String detail,
        Boolean isDefault) {
}
