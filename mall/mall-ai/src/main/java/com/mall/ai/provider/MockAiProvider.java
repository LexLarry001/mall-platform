package com.mall.ai.provider;

import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.dto.EmbeddingResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * 本地 Mock Provider：不调任何外部 API，用于开发与单元测试。
 *
 * <p>向量生成：SHA-256 哈希扩展到 1024 维（可配）→ 归一化；
 * chat：返回固定 JSON（{@code {"ok": true, "content": "...mock..."}}）。</p>
 *
 * <p>注意：本类不加 {@code @Component}，由 {@code AiAutoConfiguration} 的 {@code @Bean}
 * 统一装配。否则 {@code scanBasePackages="com.mall"} 会再扫出一个同名 Bean，
 * 与 {@code @Bean} 冲突（BeanDefinitionOverrideException）。</p>
 */
public class MockAiProvider implements AiProvider {

    private static final int DEFAULT_DIM = 128; // Mock 用小维度够了，加快测试
    private static final String MODEL_NAME = "mock-local";
    private final int dim;

    public MockAiProvider() {
        this.dim = DEFAULT_DIM;
    }

    public MockAiProvider(int dim) {
        this.dim = dim;
    }

    @Override
    public String name() {
        return MODEL_NAME;
    }

    @Override
    public boolean isEnabled() {
        return true; // Mock 永远可用，用于离线调试
    }

    @Override
    public EmbeddingResult embed(String text) {
        float[] raw = hashExtend(text == null ? "" : text, dim);
        return EmbeddingResult.of(raw, MODEL_NAME).normalize();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        // Mock 根据 userPrompt 生成结构化 JSON，便于离线测试文案生成等场景
        String content = buildMockContent(request.userPrompt());
        return ChatResult.of(content, MODEL_NAME);
    }

    /** 简易 mock：从 userPrompt 里提取商品名称，生成固定结构的文案 JSON。 */
    private String buildMockContent(String userPrompt) {
        String name = "商品";
        if (userPrompt != null) {
            int idx = userPrompt.indexOf("商品名称：");
            if (idx >= 0) {
                int start = idx + "商品名称：".length();
                int end = userPrompt.indexOf('\n', start);
                if (end < 0) end = userPrompt.length();
                name = userPrompt.substring(start, end).trim();
                if (name.isEmpty()) name = "商品";
            }
        }
        return """
                {
                  "title": "%s 高品质精选好物 限时特惠",
                  "subtitle": "%s，品质之选，值得拥有",
                  "description": "【%s】\\n\\n精选优质材料，工艺精湛，经久耐用。\\n人性化设计，使用便捷，满足日常需求。\\n品质保证，售后无忧，放心购买。",
                  "sellingPoints": ["精选材质，品质保证", "工艺精湛，经久耐用", "人性化设计，使用便捷", "售后无忧，放心购买"]
                }""".formatted(name, name, name);
    }

    /** SHA-256 生成固定长度向量：每 4 字节转一个 float，取前 dim 个。 */
    private float[] hashExtend(String text, int n) {
        float[] out = new float[n];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < n; i++) {
                // 每 4 字节拼一个 float，值域 [-1, 1]
                int idx = (i % digest.length) * 4;
                float v = ((digest[idx % digest.length] & 0xFF) - 128) / 128.0f;
                out[i] = v;
            }
        } catch (NoSuchAlgorithmException e) {
            // 理论上不会；兜底用随机
            Random r = new Random(text.hashCode());
            for (int i = 0; i < n; i++) out[i] = r.nextFloat() * 2 - 1f;
        }
        return out;
    }
}
