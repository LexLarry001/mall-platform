package com.mall.product.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商家发布/编辑商品请求。SPU 主表 + 规格定义 + SKU 列表 + Mongo 富文本详情一次提交。
 *
 * @param categoryId 平台类目 ID（必须存在且启用）
 * @param name       商品名称
 * @param subTitle   副标题/卖点
 * @param mainImg    主图 URL
 * @param imgList    图集 URL 列表
 * @param specs      规格定义（1~3 项）
 * @param skus       SKU 列表（至少一条，规格组合不可重复）
 * @param detailHtml 富文本详情（写 MongoDB product_detail）
 */
public record ProductSaveRequest(
        @NotNull(message = "类目不能为空") Long categoryId,
        @NotBlank(message = "商品名称不能为空") @Size(max = 64, message = "商品名称不能超过 64 个字符") String name,
        @Size(max = 128, message = "副标题不能超过 128 个字符") String subTitle,
        @NotBlank(message = "主图不能为空") String mainImg,
        List<String> imgList,
        List<@Valid SpecDef> specs,
        @NotEmpty(message = "SKU 至少一条") List<@Valid SkuDef> skus,
        String detailHtml) {

    /** 规格定义，如 {specName:"颜色", values:["曜石黑","月牙白"]}。 */
    public record SpecDef(
            @NotBlank(message = "规格名不能为空") String specName,
            @NotEmpty(message = "规格值不能为空") List<String> values) {
    }

    /** SKU：规格值组合 + 价格 + 库存 + 规格图。 */
    public record SkuDef(
            @NotEmpty(message = "SKU 规格组合不能为空") Map<String, String> specValues,
            @NotNull(message = "SKU 价格不能为空") @DecimalMin(value = "0.00", message = "SKU 价格不能为负") BigDecimal price,
            @NotNull(message = "SKU 库存不能为空") @Min(value = 0, message = "SKU 库存不能为负") Integer stock,
            String skuImg) {
    }
}
