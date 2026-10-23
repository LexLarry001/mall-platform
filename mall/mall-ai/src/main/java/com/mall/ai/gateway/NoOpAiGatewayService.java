package com.mall.ai.gateway;

import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.dto.EmbeddingResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * AI 关闭时的空实现：所有方法返回空结果。
 * 仅当 {@code mall.ai.enabled=false} 时装配；开启时装配 {@link AiGatewayService}。
 */
@Component
@ConditionalOnProperty(prefix = "mall.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAiGatewayService implements AiService {

    @Override public Optional<EmbeddingResult> embed(String text) { return Optional.empty(); }
    @Override public Optional<ChatResult> chat(ChatRequest request) { return Optional.empty(); }
    @Override public boolean isAiEnabled() { return false; }
    @Override public String providerName() { return "noop"; }
}
