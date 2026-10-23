package com.mall.product.copy;

/** 文案生成类型。 */
public enum ProductCopyType {

    /** 商品标题（SEO 优化，控制在 30~60 字）。 */
    TITLE,

    /** 副标题/一句话卖点。 */
    SUBTITLE,

    /** 商品详情描述（多段正文）。 */
    DESCRIPTION,

    /** 核心卖点列表（3~5 条）。 */
    SELLING_POINTS
}
