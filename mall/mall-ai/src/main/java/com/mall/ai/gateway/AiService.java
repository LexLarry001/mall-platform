package com.mall.ai.gateway;

import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.dto.EmbeddingResult;

import java.util.Optional;

/**
 * AI 统一服务接口：业务层依赖此接口，不关心实现细节。
 *
 * <p>当 {@code mall.ai.enabled=false} 时注入空实现 {@link NoOpAiGatewayService}，
 * 保证业务代码零改动。</p>
 */
public interface AiService {

    Optional<EmbeddingResult> embed(String text);

    Optional<ChatResult> chat(ChatRequest request);

    /** chat 能力是否可用（文案生成等场景）。 */
    boolean isAiEnabled();

    /** 向量能力是否可用（语义搜索、商品向量化等场景）。 */
    default boolean isEmbeddingEnabled() { return isAiEnabled(); }

    String providerName();
}
