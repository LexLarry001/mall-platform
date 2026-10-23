package com.mall.order.order.dto;

import com.mall.order.address.dto.AddressView;
import com.mall.order.cart.dto.CartItemView;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算页视图：勾选购物车项 + 默认地址 + 金额试算。
 *
 * @param defaultAddress 默认地址；买家无默认地址时为 {@code null}（前端引导新增）
 * @param items          勾选且未失效（可结算）的购物车条目
 * @param totalAmount    金额试算（sum(price*quantity)，运费固定 0）
 */
public record SettleView(AddressView defaultAddress, List<CartItemView> items, BigDecimal totalAmount) {
}
