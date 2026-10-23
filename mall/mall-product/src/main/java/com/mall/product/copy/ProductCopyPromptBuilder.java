package com.mall.product.copy;

import com.mall.product.copy.dto.ProductCopyRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品文案 Prompt 构建器。
 *
 * <p>根据风格 + 商品信息构建 system prompt 和 user prompt，
 * 要求 LLM 以固定 JSON 格式返回，便于下游解析。</p>
 */
public final class ProductCopyPromptBuilder {

    private ProductCopyPromptBuilder() {
    }

    /** 输出 JSON 结构约束（所有风格共用）。 */
    private static final String OUTPUT_FORMAT = """
            请以严格的 JSON 格式返回结果，不要包含任何 Markdown 标记或解释文字，直接输出 JSON 对象。
            JSON 结构如下：
            {
              "title": "商品标题字符串",
              "subtitle": "副标题字符串",
              "description": "详情描述字符串，可用换行分段",
              "sellingPoints": ["卖点1", "卖点2", "卖点3"]
            }
            注意：
            - title 控制在 30~60 字，包含核心关键词，适合搜索
            - subtitle 一句话卖点，不超过 30 字
            - description 200~500 字，分 2~4 段
            - sellingPoints 3~5 条，每条不超过 20 字
            - 只返回 JSON，不要返回任何其他内容""";

    /** 构建 system prompt（按风格定制）。 */
    public static String buildSystemPrompt(ProductCopyStyle style) {
        String role = switch (style) {
            case ECOMMERCE ->
                "你是一名资深电商文案策划，擅长撰写淘宝/京东详情页文案。"
                        + "风格特点：卖点突出、结构清晰、善用数字和对比、激发购买欲。";
            case XIAOHONGSHU ->
                "你是一名小红书爆款笔记写手，擅长用口语化、情绪化的语言种草。"
                        + "风格特点：第一人称、真实体验感、适当使用 emoji、结尾带话题感。";
            case MINIMALIST ->
                "你是一名极简主义文案师，擅长用最少的字传达核心价值。"
                        + "风格特点：短平快、直击痛点、不加修饰。";
            case PROFESSIONAL ->
                "你是一名产品参数分析师，擅长客观呈现产品规格和工艺。"
                        + "风格特点：数据准确、参数详实、用词专业、不带主观情绪。";
        };
        return role + "\n\n" + OUTPUT_FORMAT;
    }

    /** 构建 user prompt（填入商品信息）。 */
    public static String buildUserPrompt(ProductCopyRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下商品生成文案：\n");
        sb.append("- 商品名称：").append(safe(req.name())).append("\n");
        if (req.categoryName() != null && !req.categoryName().isBlank()) {
            sb.append("- 所属类目：").append(req.categoryName()).append("\n");
        }
        if (req.brand() != null && !req.brand().isBlank()) {
            sb.append("- 品牌：").append(req.brand()).append("\n");
        }
        if (req.price() != null && req.price().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("- 价格：¥").append(req.price().stripTrailingZeros().toPlainString()).append("\n");
        }
        if (req.specs() != null && !req.specs().isEmpty()) {
            sb.append("- 规格参数：\n");
            for (ProductCopyRequest.SpecInfo spec : req.specs()) {
                String values = spec.values() == null ? "" : String.join("、", spec.values());
                sb.append("  · ").append(spec.specName()).append("：").append(values).append("\n");
            }
        }
        if (req.sellingPoints() != null && !req.sellingPoints().isEmpty()) {
            sb.append("- 原始卖点关键词：").append(String.join("、", req.sellingPoints())).append("\n");
        }

        List<ProductCopyType> types = req.typesOrDefault();
        String typeDesc = types.stream()
                .map(ProductCopyType::name)
                .collect(Collectors.joining("、"));
        sb.append("\n需要生成的文案类型：").append(typeDesc).append("\n");
        sb.append("风格要求：").append(req.styleOrDefault().getDisplay()).append("\n");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
