package com.mall.product.product.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商家商品详情回显视图：SPU + 规格 + SKU + Mongo 富文本详情（编辑页回显用）。
 */
public record MerchantProductDetailView(
        Long spuId,
        Long categoryId,
        String name,
        String subTitle,
        String mainImg,
        List<String> imgList,
        Integer status,
        String auditRemark,
        List<SpecView> specs,
        List<SkuView> skus,
        String detailHtml) {

    /** 规格定义回显。 */
    public record SpecView(String specName, List<String> values) {
    }

    /** SKU 回显。 */
    public record SkuView(Long skuId, Map<String, String> specValues, BigDecimal price,
                          Integer stock, String skuImg) {
    }
}
