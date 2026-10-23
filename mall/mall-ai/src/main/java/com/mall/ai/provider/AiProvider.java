package com.mall.ai.provider;

import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.dto.EmbeddingResult;

/**
 * AI Provider 抽象：多模型可切换（百炼 / DeepSeek / 豆包 / Mock）。
 *
 * <p>业务层通过 {@link com.mall.ai.gateway.AiGatewayService} 间接使用，
 * 本接口不直接暴露给业务模块。</p>
 */
public interface AiProvider {

    String name();

    boolean isEnabled();

    /** 该端点是否支持向量生成（DeepSeek 只做 chat，不支持）。 */
    default boolean supportsEmbedding() { return true; }

    /** 该端点是否支持 chat 补全。 */
    default boolean supportsChat() { return true; }

    /** 文本转向量；失败时抛异常由上层 Gateway 捕获并降级。 */
    EmbeddingResult embed(String text);

    /** Chat 完成；失败时抛异常由上层 Gateway 捕获并降级。 */
    ChatResult chat(ChatRequest request);
}
