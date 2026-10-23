package com.mall.product.copy;

/**
 * 文案生成风格。
 *
 * <p>不同风格对应不同的 system prompt，影响语气、结构、长度。</p>
 */
public enum ProductCopyStyle {

    /** 电商详情风：突出卖点、参数、使用场景，适合淘宝/京东详情页。 */
    ECOMMERCE("电商详情风"),

    /** 小红书种草风：口语化、情绪化、带 emoji，适合内容种草。 */
    XIAOHONGSHU("小红书种草风"),

    /** 极简风：短平快，一句话卖点。 */
    MINIMALIST("极简风"),

    /** 专业参数风：侧重材质、尺寸、工艺等客观参数。 */
    PROFESSIONAL("专业参数风");

    private final String display;

    ProductCopyStyle(String display) {
        this.display = display;
    }

    public String getDisplay() {
        return display;
    }
}
