package com.mall.ai.dto;

import java.util.List;

/** Chat 请求：只带必要字段，简单版。 */
public record ChatRequest(
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens) {

    public ChatRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, 0.2, 1024);
    }
}
