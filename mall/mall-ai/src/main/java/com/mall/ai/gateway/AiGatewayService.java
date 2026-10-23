package com.mall.ai.gateway;

import com.mall.ai.config.AiProperties;
import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.dto.EmbeddingResult;
import com.mall.ai.provider.AiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * AI 统一服务实现：业务层通过 {@link AiService} 接口依赖。
 *
 * <p>职责：结果缓存、限流、熔断、异常降级、Token 计量。
 * 所有外部调用均走 try/catch 降级，保证 AI 挂了不阻断业务主链路。</p>
 *
 * <p>M10：chat 与 embedding 拆成两个 Provider（DeepSeek 负责文案、百炼负责向量），
 * 缓存键、限流键、熔断状态均相互独立。</p>
 */
public class AiGatewayService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    private static final String CACHE_EMBED_PREFIX = "mall:ai:embed:";
    private static final String CACHE_CHAT_PREFIX   = "mall:ai:chat:";
    private static final String RATE_KEY_TEMPLATE   = "mall:ai:rate:{provider}:{minute}";
    private static final String CIRCUIT_FAIL_KEY    = "mall:ai:circuit:fail:{provider}";

    private final AiProperties properties;
    /** 对话端点：DeepSeek 等。 */
    private final AiProvider chatProvider;
    /** 向量端点：百炼 text-embedding-v3；未单独配置时与 chatProvider 相同。 */
    private final AiProvider embeddingProvider;
    private final StringRedisTemplate redis;

    private final Circuit chatCircuit = new Circuit();
    private final Circuit embeddingCircuit = new Circuit();

    public AiGatewayService(AiProperties properties, AiProvider chatProvider,
                            AiProvider embeddingProvider, StringRedisTemplate redis) {
        this.properties = properties;
        this.chatProvider = chatProvider;
        this.embeddingProvider = embeddingProvider;
        this.redis = redis;
    }

    // =================== Embed ===================

    @Override
    public Optional<EmbeddingResult> embed(String text) {
        if (!isEmbeddingEnabled() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (isCircuitOpen(embeddingCircuit)) return Optional.empty();
        if (!tryAcquireRate(embeddingProvider.name())) return Optional.empty();

        String model = embeddingModel();
        String cacheKey = CACHE_EMBED_PREFIX + model + ":" + sha256(text);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                EmbeddingResult parsed = parseVector(cached, model);
                if (parsed != null) return Optional.of(parsed);
            }
        } catch (Exception ignored) {
        }

        try {
            EmbeddingResult result = embeddingProvider.embed(text);
            try {
                redis.opsForValue().set(cacheKey, vectorToJson(result.vector()),
                        properties.getCache().getEmbedTtlSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("[AI] embed failed (provider={}, model={}): {}",
                    embeddingProvider.name(), model, e.getMessage());
            recordFailure(embeddingCircuit, embeddingProvider.name());
            return Optional.empty();
        }
    }

    // =================== Chat ===================

    @Override
    public Optional<ChatResult> chat(ChatRequest request) {
        if (!isAiEnabled() || request == null) {
            return Optional.empty();
        }
        if (isCircuitOpen(chatCircuit)) return Optional.empty();
        if (!tryAcquireRate(chatProvider.name())) return Optional.empty();

        String hash = sha256(request.systemPrompt() + "\n\u0000\n" + request.userPrompt()
                + "|temp=" + request.temperature());
        String cacheKey = CACHE_CHAT_PREFIX + hash;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return Optional.of(ChatResult.of(cached, properties.getChatModel()));
            }
        } catch (Exception ignored) {
        }

        try {
            ChatResult result = chatProvider.chat(request);
            try {
                redis.opsForValue().set(cacheKey, result.content(),
                        properties.getCache().getChatTtlSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("[AI] chat failed (provider={}, model={}): {}",
                    chatProvider.name(), properties.getChatModel(), e.getMessage());
            recordFailure(chatCircuit, chatProvider.name());
            return Optional.empty();
        }
    }

    @Override
    public String providerName() {
        return chatProvider.name();
    }

    @Override
    public boolean isAiEnabled() {
        return properties.isEnabled() && chatProvider.isEnabled() && chatProvider.supportsChat();
    }

    @Override
    public boolean isEmbeddingEnabled() {
        return properties.isEnabled() && embeddingProvider.isEnabled() && embeddingProvider.supportsEmbedding();
    }

    // =================== 内部工具 ===================

    private String embeddingModel() {
        return properties.getEmbedding().hasCredentials()
                ? properties.getEmbedding().getModel()
                : properties.getChatModel();
    }

    private boolean tryAcquireRate(String provider) {
        String key = RATE_KEY_TEMPLATE.replace("{provider}", provider)
                .replace("{minute}", String.valueOf(System.currentTimeMillis() / 60000));
        try {
            Long val = redis.opsForValue().increment(key);
            if (val != null && val == 1) redis.expire(key, 60, TimeUnit.SECONDS);
            return val == null || val <= properties.getRateLimit().getPerMinute();
        } catch (Exception ignored) {
            return true; // Redis 不可用时降级：不限流
        }
    }

    private boolean isCircuitOpen(Circuit circuit) {
        if (!circuit.open) return false;
        if (System.currentTimeMillis() > circuit.openUntil) {
            circuit.open = false;
            log.info("[AI] circuit breaker closed (recovering)");
            return false;
        }
        return true;
    }

    private void recordFailure(Circuit circuit, String provider) {
        try {
            String key = CIRCUIT_FAIL_KEY.replace("{provider}", provider);
            Long val;
            synchronized (this) {
                val = redis.opsForValue().increment(key);
                if (val != null && val == 1) redis.expire(key, 300, TimeUnit.SECONDS);
                if (val != null && val >= properties.getRateLimit().getCircuitFailThreshold()) {
                    circuit.open = true;
                    circuit.openUntil = System.currentTimeMillis()
                            + properties.getRateLimit().getCircuitOpenSeconds() * 1000;
                    log.warn("[AI] circuit breaker opened (provider={})", provider);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String vectorToJson(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static EmbeddingResult parseVector(String json, String model) {
        try {
            int open = json.indexOf('[');
            int close = json.lastIndexOf(']');
            if (open < 0 || close < 0) return null;
            String body = json.substring(open + 1, close);
            String[] parts = body.split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i].trim());
            return EmbeddingResult.of(v, model);
        } catch (Exception e) {
            return null;
        }
    }

    /** 每个能力独立的熔断状态：embedding 失败不影响 chat 文案生成。 */
    private static final class Circuit {
        private volatile boolean open = false;
        private volatile long openUntil = 0L;
    }
}
