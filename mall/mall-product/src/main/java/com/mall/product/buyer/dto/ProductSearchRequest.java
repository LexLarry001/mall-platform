package com.mall.product.buyer.dto;

/**
 * 买家端商品搜索请求参数。
 *
 * @param sort default 综合（更新时间倒序）/ sales 销量 / price_asc 价格升 / price_desc 价格降 / new 新品
 */
public record ProductSearchRequest(String keyword, Long categoryId, Long shopId, String sort,
                                   long page, long size) {
}
