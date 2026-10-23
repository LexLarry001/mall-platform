package com.mall.order.review.dto;

import java.util.List;
import java.util.Map;

/**
 * 商品评价分页结果。
 *
 * @param records    当前页评价
 * @param total      评价总数
 * @param ratingStat 星级分布，key 为 1~5，value 为条数
 */
public record ReviewPageView(List<ReviewView> records, long total, Map<Integer, Long> ratingStat) {
}
