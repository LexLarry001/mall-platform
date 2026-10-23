package com.mall.product.copy.dto;

import java.util.List;

/**
 * 商品文案生成结果。
 *
 * @param title          生成的标题
 * @param subtitle       生成的副标题
 * @param description    生成的详情描述
 * @param sellingPoints  生成的核心卖点列表
 * @param source         文案来源：ai / template（AI 不可用时走模板）
 * @param model          所用模型名（template 时为 "template"）
 */
public record ProductCopyResult(
        String title,
        String subtitle,
        String description,
        List<String> sellingPoints,
        String source,
        String model) {

    public static ProductCopyResult of(String title, String subtitle, String description,
                                       List<String> sellingPoints, String source, String model) {
        return new ProductCopyResult(title, subtitle, description, sellingPoints, source, model);
    }
}
