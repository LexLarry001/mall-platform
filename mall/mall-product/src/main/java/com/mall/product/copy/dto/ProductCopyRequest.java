package com.mall.product.copy.dto;

import com.mall.product.copy.ProductCopyStyle;
import com.mall.product.copy.ProductCopyType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品文案生成请求。
 *
 * @param name          商品名称（必填）
 * @param categoryName  类目名称（如"书桌"，可选）
 * @param brand         品牌（可选）
 * @param price         价格（可选）
 * @param specs         规格定义列表，如 [{specName:"材质", values:["实木","板材"]}]
 * @param sellingPoints 商家提供的原始卖点关键词（可选，AI 会润色）
 * @param style         文案风格
 * @param types         需要生成的文案类型
 */
public record ProductCopyRequest(
        String name,
        String categoryName,
        String brand,
        BigDecimal price,
        List<SpecInfo> specs,
        List<String> sellingPoints,
        ProductCopyStyle style,
        List<ProductCopyType> types) {

    /** 规格信息。 */
    public record SpecInfo(String specName, List<String> values) {
    }

    public ProductCopyStyle styleOrDefault() {
        return style == null ? ProductCopyStyle.ECOMMERCE : style;
    }

    public List<ProductCopyType> typesOrDefault() {
        return (types == null || types.isEmpty())
                ? List.of(ProductCopyType.TITLE, ProductCopyType.SUBTITLE,
                ProductCopyType.DESCRIPTION, ProductCopyType.SELLING_POINTS)
                : types;
    }
}
