package com.mall.product.buyer.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 买家端商品详情聚合读模型。
 *
 * <p>整体以 JSON 缓存在 {@code mall:product:{spuId}}（TTL 30 分钟），商品变更后由
 * 写操作删键、下一次读取重建（plan RF5）。结构仅含买家需要展示的字段，
 * 不含 status/auditRemark 等内部状态。</p>
 */
public record ProductDetailVO(SpuView spu, List<SkuView> skus, List<SpecView> specs,
                              String detailHtml, ShopView shop) {

    /** SPU 展示信息。 */
    public record SpuView(Long id, Long shopId, Long categoryId, String name, String subTitle,
                          String mainImg, List<String> imgList, BigDecimal price, Integer sales,
                          BigDecimal avgRating, Integer commentCount) {
    }

    /** SKU 展示信息（规格组合已解析为键值对）。 */
    public record SkuView(Long id, Map<String, String> specValues, BigDecimal price, Integer stock,
                          String skuImg) {
    }

    /** 规格定义（规格名 + 可选值）。 */
    public record SpecView(String specName, List<String> values) {
    }

    /** 店铺卡片信息。 */
    public record ShopView(Long id, String name, String logo, String intro, Integer bizStatus) {
    }
}
