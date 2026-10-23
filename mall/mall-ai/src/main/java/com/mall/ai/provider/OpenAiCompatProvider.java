package com.mall.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.config.AiProperties;
import com.mall.ai.dto.ChatRequest;
import com.mall.ai.dto.ChatResult;
import com.mall.ai.dto.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议 Provider（百炼 / DeepSeek / 豆包均可）。
 *
 * <p>走原生 {@link java.net.http.HttpClient}，避免引入额外 HTTP 依赖；
 * 超时 / 重试由上层 {@link com.mall.ai.gateway.AiGatewayService} 统一处理。</p>
 *
 * <p>同一个实例只绑定一个端点：{@link #forChat(AiProperties)} 负责对话，
 * {@link #forEmbedding(AiProperties)} 负责向量。这样 chat 走 DeepSeek、
 * embedding 走百炼时两套 baseUrl / apiKey 互不干扰。</p>
 */
public class OpenAiCompatProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client;
    private final String name;
    private final String baseUrl;
    private final String apiKey;
    /** null 表示该端点不提供 chat。 */
    private final String chatModel;
    /** null 表示该端点不提供 /embeddings。 */
    private final String embeddingModel;
    private final long timeoutMs;

    private OpenAiCompatProvider(String name, String baseUrl, String apiKey,
                                 String chatModel, String embeddingModel, long timeoutMs) {
        this.name = name;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.timeoutMs = timeoutMs;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /** 对话端点（DeepSeek / 百炼 chat 模型）。 */
    public static OpenAiCompatProvider forChat(AiProperties props) {
        return new OpenAiCompatProvider(props.getProvider(), props.getBaseUrl(), props.getApiKey(),
                props.getChatModel(), null, props.getTimeoutMs());
    }

    /** 向量端点（百炼 text-embedding-v3）。 */
    public static OpenAiCompatProvider forEmbedding(AiProperties props) {
        AiProperties.Embedding e = props.getEmbedding();
        return new OpenAiCompatProvider(e.getProvider(), e.getBaseUrl(), e.getApiKey(),
                null, e.getModel(), e.getTimeoutMs());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isEnabled() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public boolean supportsEmbedding() {
        return embeddingModel != null && !embeddingModel.isBlank();
    }

    @Override
    public boolean supportsChat() {
        return chatModel != null && !chatModel.isBlank();
    }

    @Override
    public EmbeddingResult embed(String text) {
        if (!supportsEmbedding()) {
            throw new UnsupportedOperationException("provider " + name + " has no embedding endpoint");
        }
        String body = json(Map.of(
                "model", embeddingModel,
                "input", text
        ));
        String resp = post(baseUrl + "/embeddings", body);
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode vec = root.path("data").path(0).path("embedding");
            int dim = vec.size();
            if (dim == 0) {
                throw new IllegalStateException("empty embedding in response: " + resp);
            }
            float[] out = new float[dim];
            for (int i = 0; i < dim; i++) out[i] = (float) vec.get(i).asDouble();
            return EmbeddingResult.of(out, embeddingModel).normalize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse embedding response: " + resp, e);
        }
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        if (!supportsChat()) {
            throw new UnsupportedOperationException("provider " + name + " has no chat endpoint");
        }
        String body = json(Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())
                ),
                "temperature", request.temperature(),
                "max_tokens", request.maxTokens()
        ));
        String resp = post(baseUrl + "/chat/completions", body);
        try {
            JsonNode root = objectMapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            int inputTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = root.path("usage").path("completion_tokens").asInt(0);
            return new ChatResult(content, inputTokens, outputTokens, chatModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse chat response: " + resp, e);
        }
    }

    private String post(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[AI] {} responded HTTP {}: {}", name, resp.statusCode(), resp.body());
                throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed to " + url + ": " + e.getMessage(), e);
        }
    }

    private String json(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }
}
