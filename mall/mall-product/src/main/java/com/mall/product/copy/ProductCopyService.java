package com.mall.product.copy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.gateway.AiService;
import com.mall.product.copy.dto.ProductCopyRequest;
import com.mall.product.copy.dto.ProductCopyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品文案生成服务。
 *
 * <p>调用链路：AI 生成 → 空结果重试（扰动 prompt）→ 模板降级。
 * 保证 AI 不可用或返回空时，业务仍能拿到可用文案。</p>
 */
@Service
public class ProductCopyService {

    private static final Logger log = LoggerFactory.getLogger(ProductCopyService.class);
    private static final String SOURCE_AI = "ai";
    private static final String SOURCE_TEMPLATE = "template";
    private static final int MAX_RETRY = 2;

    private final AiService aiService;
    private final ObjectMapper objectMapper;

    public ProductCopyService(AiService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成商品文案。
     *
     * <p>优先 AI 生成；AI 不可用或返回空时自动重试（最多 2 次，每次扰动 prompt），
     * 仍失败则回退到模板生成。</p>
     */
    public ProductCopyResult generate(ProductCopyRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("商品名称不能为空");
        }

        ProductCopyStyle style = request.styleOrDefault();

        // AI 可用时尝试生成
        if (aiService.isAiEnabled()) {
            for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
                Optional<ProductCopyResult> aiResult = tryAiGenerate(request, style, attempt);
                if (aiResult.isPresent()) {
                    log.info("[AI] copy generated for product '{}' (attempt={})", request.name(), attempt);
                    return aiResult.get();
                }
            }
            log.warn("[AI] copy generation failed after {} attempts, fallback to template", MAX_RETRY + 1);
        }

        // 模板降级
        return generateByTemplate(request, style);
    }

    /** 单次 AI 生成尝试；返回空 Optional 表示需要重试或降级。 */
    private Optional<ProductCopyResult> tryAiGenerate(ProductCopyRequest request,
                                                       ProductCopyStyle style, int attempt) {
        try {
            String systemPrompt = ProductCopyPromptBuilder.buildSystemPrompt(style);
            // 重试时给 user prompt 加扰动，避免相同输入重复失败
            String userPrompt = attempt == 0
                    ? ProductCopyPromptBuilder.buildUserPrompt(request)
                    : ProductCopyPromptBuilder.buildUserPrompt(request)
                            + "\n（请重新生成，确保内容不为空，风格更" + style.getDisplay() + "一些）";

            ChatRequest chatReq = new ChatRequest(systemPrompt, userPrompt, 0.7, 1500);
            Optional<ChatResult> chatOpt = aiService.chat(chatReq);
            if (chatOpt.isEmpty()) {
                return Optional.empty();
            }

            String content = chatOpt.get().content();
            if (!StringUtils.hasText(content)) {
                log.debug("[AI] empty content on attempt {}", attempt);
                return Optional.empty();
            }

            ProductCopyResult parsed = parseCopy(content, chatOpt.get().model());
            if (parsed == null) {
                log.debug("[AI] parse failed on attempt {}, content={}", attempt, content);
                return Optional.empty();
            }
            // 关键结果非空校验
            if (!isResultUsable(parsed)) {
                log.debug("[AI] result not usable on attempt {}", attempt);
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (Exception e) {
            log.warn("[AI] copy generation exception on attempt {}: {}", attempt, e.getMessage());
            return Optional.empty();
        }
    }

    /** 解析 LLM 返回的 JSON 文案。 */
    private ProductCopyResult parseCopy(String content, String model) {
        try {
            // 去除可能的 Markdown 代码块标记
            String json = content.trim();
            if (json.startsWith("```")) {
                int first = json.indexOf('\n');
                int last = json.lastIndexOf("```");
                if (first > 0 && last > first) {
                    json = json.substring(first + 1, last).trim();
                }
            }
            JsonNode root = objectMapper.readTree(json);
            String title = root.path("title").asText("");
            String subtitle = root.path("subtitle").asText("");
            String description = root.path("description").asText("");
            List<String> sellingPoints = new ArrayList<>();
            JsonNode sp = root.path("sellingPoints");
            if (sp.isArray()) {
                for (JsonNode node : sp) {
                    String v = node.asText("");
                    if (StringUtils.hasText(v)) sellingPoints.add(v);
                }
            }
            return ProductCopyResult.of(title, subtitle, description, sellingPoints, SOURCE_AI, model);
        } catch (Exception e) {
            return null;
        }
    }

    /** 结果可用性校验：至少 title 或 description 非空。 */
    private boolean isResultUsable(ProductCopyResult r) {
        return StringUtils.hasText(r.title()) || StringUtils.hasText(r.description());
    }

    // =================== 模板降级 ===================

    private ProductCopyResult generateByTemplate(ProductCopyRequest request, ProductCopyStyle style) {
        String name = request.name();
        String category = request.categoryName() == null ? "" : request.categoryName();
        String brand = request.brand() == null ? "" : request.brand();
        BigDecimal price = request.price();

        String title = buildTemplateTitle(name, category, brand, price);
        String subtitle = buildTemplateSubtitle(name, category, style);
        String description = buildTemplateDescription(name, category, brand, price, request.sellingPoints(), style);
        List<String> sellingPoints = buildTemplateSellingPoints(name, category, request.sellingPoints());

        return ProductCopyResult.of(title, subtitle, description, sellingPoints, SOURCE_TEMPLATE, SOURCE_TEMPLATE);
    }

    private String buildTemplateTitle(String name, String category, String brand, BigDecimal price) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(brand)) sb.append(brand).append(" ");
        sb.append(name);
        if (StringUtils.hasText(category) && !name.contains(category)) {
            sb.append(" ").append(category);
        }
        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(" 限时特惠");
        }
        return sb.toString();
    }

    private String buildTemplateSubtitle(String name, String category, ProductCopyStyle style) {
        return switch (style) {
            case XIAOHONGSHU -> "闭眼入！" + name + "真的太好用了～";
            case MINIMALIST -> name + "，简单好用。";
            case PROFESSIONAL -> name + "，精选材质，匠心工艺。";
            default -> StringUtils.hasText(category)
                    ? category + "精选，品质之选。"
                    : "品质好物，值得拥有。";
        };
    }

    private String buildTemplateDescription(String name, String category, String brand,
                                            BigDecimal price, List<String> sellingPoints,
                                            ProductCopyStyle style) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append("】\n\n");
        if (StringUtils.hasText(brand)) {
            sb.append("品牌：").append(brand).append("\n");
        }
        if (StringUtils.hasText(category)) {
            sb.append("类目：").append(category).append("\n");
        }
        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("价格：¥").append(price.stripTrailingZeros().toPlainString()).append("\n");
        }
        sb.append("\n");
        if (sellingPoints != null && !sellingPoints.isEmpty()) {
            sb.append("产品特点：\n");
            for (String p : sellingPoints) {
                sb.append("· ").append(p).append("\n");
            }
        } else {
            sb.append("产品特点：\n");
            sb.append("· 精选材质，品质保证\n");
            sb.append("· 工艺精湛，经久耐用\n");
            sb.append("· 人性化设计，使用便捷\n");
        }
        if (style == ProductCopyStyle.XIAOHONGSHU) {
            sb.append("\n姐妹们真的可以冲！性价比超高～\n");
        }
        return sb.toString();
    }

    private List<String> buildTemplateSellingPoints(String name, String category, List<String> original) {
        if (original != null && !original.isEmpty()) {
            return List.copyOf(original);
        }
        List<String> points = new ArrayList<>();
        points.add("精选材质，品质保证");
        points.add("工艺精湛，经久耐用");
        points.add(StringUtils.hasText(category) ? category + "优选" : "好物优选");
        points.add("售后无忧，放心购买");
        return points;
    }
}
