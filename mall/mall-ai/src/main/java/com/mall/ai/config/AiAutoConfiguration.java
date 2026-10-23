package com.mall.ai.config;

import com.mall.ai.gateway.AiGatewayService;
import com.mall.ai.gateway.AiService;
import com.mall.ai.provider.AiProvider;
import com.mall.ai.provider.MockAiProvider;
import com.mall.ai.provider.OpenAiCompatProvider;
import com.mall.ai.vector.InMemoryVectorStore;
import com.mall.ai.vector.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * mall-ai 自动配置。
 *
 * <p>当 {@code mall.ai.enabled=true} 时装配真实 Bean：Provider + Gateway + VectorStore；
 * 否则仅装配 {@link com.mall.ai.gateway.NoOpAiGatewayService}（自带 matchIfMissing=true）。</p>
 *
 * <p>chat 与 embedding 允许指向不同厂商：chat 走 {@code mall.ai.*}（DeepSeek），
 * embedding 走 {@code mall.ai.embedding.*}（百炼）；未配置 embedding Key 时复用 chat Provider。</p>
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "mall.ai", name = "enabled", havingValue = "true")
    public AiService aiGatewayService(AiProperties properties, StringRedisTemplate redis) {
        AiProvider chatProvider = buildChatProvider(properties);
        AiProvider embeddingProvider = buildEmbeddingProvider(properties, chatProvider);
        return new AiGatewayService(properties, chatProvider, embeddingProvider, redis);
    }

    private static AiProvider buildChatProvider(AiProperties properties) {
        if ("openai-compat".equalsIgnoreCase(properties.getProvider())) {
            return OpenAiCompatProvider.forChat(properties);
        }
        return new MockAiProvider();
    }

    private static AiProvider buildEmbeddingProvider(AiProperties properties, AiProvider chatProvider) {
        return properties.getEmbedding().hasCredentials()
                ? OpenAiCompatProvider.forEmbedding(properties)
                : chatProvider; // 未单独配置向量端点：mock 可离线出向量，DeepSeek 则自动视为不可用
    }

    // ========== VectorStore（始终装配，空 Map 无额外开销） ==========

    @Bean
    public VectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }
}
