package com.mall.ai.dto;

/** Chat 结果：文本 + token 消耗。 */
public record ChatResult(
        String content,
        int inputTokens,
        int outputTokens,
        String model) {

    public static ChatResult of(String content, String model) {
        return new ChatResult(content, 0, 0, model);
    }
}
