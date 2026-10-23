package com.mall.order.cart;

/**
 * 购物车 Hash value（JSON 序列化）：{@code {"quantity":2,"checked":true}}。
 *
 * <p>Redis Hash {@code mall:cart:{userId}} 的 field 为 skuId，value 为本记录序列化串。</p>
 */
record CartEntry(int quantity, boolean checked) {
}
