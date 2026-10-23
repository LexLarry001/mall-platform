package com.mall.product.copy;

import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.gateway.AiService;
import com.mall.product.copy.dto.ProductCopyRequest;
import com.mall.product.copy.dto.ProductCopyResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 商品文案生成服务单测。
 *
 * <p>钉死项：AI 可用时返回结构化文案；AI 不可用时走模板降级；空结果重试后仍失败则降级。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductCopyServiceTest {

    @Mock
    private AiService aiService;

    private ProductCopyService copyService;

    @BeforeEach
    void setUp() {
        copyService = new ProductCopyService(aiService, new ObjectMapper());
    }

    /** AI 可用且返回有效 JSON → 解析成功，source=ai。 */
    @Test
    void generate_withAi_returnsParsedCopy() {
        when(aiService.isAiEnabled()).thenReturn(true);
        String aiJson = """
                {
                  "title": "实木书桌 环保材质 学习办公首选",
                  "subtitle": "一张好书桌，陪伴每一天",
                  "description": "采用进口实木制作，环保漆面，承重稳固。",
                  "sellingPoints": ["实木材质", "环保漆面", "稳固承重"]
                }""";
        when(aiService.chat(any(ChatRequest.class)))
                .thenReturn(Optional.of(ChatResult.of(aiJson, "mock-local")));

        ProductCopyResult result = copyService.generate(request());

        assertThat(result.source()).isEqualTo("ai");
        assertThat(result.title()).isEqualTo("实木书桌 环保材质 学习办公首选");
        assertThat(result.subtitle()).isEqualTo("一张好书桌，陪伴每一天");
        assertThat(result.description()).contains("实木");
        assertThat(result.sellingPoints()).hasSize(3);
    }

    /** AI 不可用 → 模板降级，source=template。 */
    @Test
    void generate_withoutAi_fallsBackToTemplate() {
        when(aiService.isAiEnabled()).thenReturn(false);

        ProductCopyResult result = copyService.generate(request());

        assertThat(result.source()).isEqualTo("template");
        assertThat(result.title()).contains("实木书桌");
        assertThat(result.subtitle()).isNotBlank();
        assertThat(result.description()).contains("实木书桌");
        assertThat(result.sellingPoints()).isNotEmpty();
    }

    /** AI 返回空字符串 → 重试后降级到模板。 */
    @Test
    void generate_withEmptyAiResponse_fallsBackToTemplate() {
        when(aiService.isAiEnabled()).thenReturn(true);
        when(aiService.chat(any(ChatRequest.class)))
                .thenReturn(Optional.of(ChatResult.of("   ", "mock-local")));

        ProductCopyResult result = copyService.generate(request());

        assertThat(result.source()).isEqualTo("template");
        assertThat(result.title()).contains("实木书桌");
    }

    /** AI 返回无效 JSON → 重试后降级到模板。 */
    @Test
    void generate_withInvalidJson_fallsBackToTemplate() {
        when(aiService.isAiEnabled()).thenReturn(true);
        when(aiService.chat(any(ChatRequest.class)))
                .thenReturn(Optional.of(ChatResult.of("not a json at all", "mock-local")));

        ProductCopyResult result = copyService.generate(request());

        assertThat(result.source()).isEqualTo("template");
    }

    /** AI 返回缺字段的 JSON（title 和 description 都空）→ 视为不可用，降级。 */
    @Test
    void generate_withEmptyFields_fallsBackToTemplate() {
        when(aiService.isAiEnabled()).thenReturn(true);
        String aiJson = """
                {"title": "", "subtitle": "", "description": "", "sellingPoints": []}""";
        when(aiService.chat(any(ChatRequest.class)))
                .thenReturn(Optional.of(ChatResult.of(aiJson, "mock-local")));

        ProductCopyResult result = copyService.generate(request());

        assertThat(result.source()).isEqualTo("template");
    }

    /** AI 抛异常 → 降级到模板。 */
    @Test
    void generate_withAiException_fallsBackToTemplate() {
        when(aiService.isAiEnabled()).thenReturn(true);
        when(aiService.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("network error"));

        ProductCopyResult result = copyService.generate(request());

        assertThat(result.source()).isEqualTo("template");
    }

    /** 商品名称为空 → 抛异常。 */
    @Test
    void generate_withEmptyName_throws() {
        ProductCopyRequest emptyName = new ProductCopyRequest(
                "", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> copyService.generate(emptyName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("商品名称不能为空");
    }

    /** 小红书风格模板降级。 */
    @Test
    void generate_xiaohongshuStyle_templateHasEmojiTone() {
        when(aiService.isAiEnabled()).thenReturn(false);

        ProductCopyRequest req = new ProductCopyRequest(
                "北欧风休闲椅", "休闲椅", "宜家",
                new BigDecimal("299.00"),
                List.of(new ProductCopyRequest.SpecInfo("材质", List.of("布艺", "实木"))),
                List.of("久坐不累", "颜值高"),
                ProductCopyStyle.XIAOHONGSHU, List.of(ProductCopyType.TITLE));

        ProductCopyResult result = copyService.generate(req);

        assertThat(result.source()).isEqualTo("template");
        assertThat(result.subtitle()).contains("闭眼入");
        assertThat(result.description()).contains("姐妹们");
    }

    private ProductCopyRequest request() {
        return new ProductCopyRequest(
                "实木书桌", "书桌", "源氏木语",
                new BigDecimal("899.00"),
                List.of(new ProductCopyRequest.SpecInfo("材质", List.of("橡木", "松木"))),
                List.of("环保材质", "稳固承重"),
                ProductCopyStyle.ECOMMERCE,
                List.of(ProductCopyType.TITLE, ProductCopyType.DESCRIPTION));
    }
}
