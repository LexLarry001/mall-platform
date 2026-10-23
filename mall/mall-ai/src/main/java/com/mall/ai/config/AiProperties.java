package com.mall.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 配置：全部走环境变量，Key 不入库进 Git。 */
@ConfigurationProperties(prefix = "mall.ai")
public class AiProperties {

    /** 总开关：false 时整个 AI 链路不装配。 */
    private boolean enabled = false;

    /** chat provider 名：mock / openai-compat。 */
    private String provider = "mock";

    /** OpenAI 兼容协议 URL（chat 端点，如 DeepSeek、百炼）。 */
    private String baseUrl = "";

    /** chat 端点 API Key（环境变量注入，不得入库）。 */
    private String apiKey = "";

    private String chatModel = "qwen-plus";
    private long timeoutMs = 8000;
    private int maxRetries = 1;

    private final Embedding embedding = new Embedding();
    private final Search search = new Search();
    private final Cache cache = new Cache();
    private final RateLimit rateLimit = new RateLimit();

    // getters & setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Embedding getEmbedding() { return embedding; }
    public Search getSearch() { return search; }
    public Cache getCache() { return cache; }
    public RateLimit getRateLimit() { return rateLimit; }

    /**
     * 向量模型独立端点配置。
     *
     * <p>单独拆出来的原因：DeepSeek 只提供 chat 没有 {@code /embeddings}，
     * 向量必须走百炼（DashScope）等另一家服务。未配置独立 Key 时回落到 chat provider，
     * 保证 mock 模式与单 provider 模式行为不变。</p>
     */
    public static class Embedding {
        /** 是否尝试使用独立 embedding 端点（未配置 Key 时自动回落）。 */
        private boolean enabled = true;
        /** 仅用于日志与限流键，如 bailian。 */
        private String provider = "bailian";
        /** 百炼 OpenAI 兼容模式地址。 */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        /** 百炼 API Key（环境变量注入）。 */
        private String apiKey = "";
        /** text-embedding-v3 支持 1024 / 768 / 512 维。 */
        private String model = "text-embedding-v3";
        private int dim = 1024;
        private long timeoutMs = 10000;

        /** 是否已配置可用的独立向量端点。 */
        public boolean hasCredentials() {
            return enabled && apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDim() { return dim; }
        public void setDim(int dim) { this.dim = dim; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Search {
        /** keyword / hybrid / auto（auto=有关键词+有向量则 hybrid）。 */
        private String mode = "keyword";
        private int keywordTopK = 200;
        private int vectorTopK = 50;
        private int rrfK = 60;
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getKeywordTopK() { return keywordTopK; }
        public void setKeywordTopK(int keywordTopK) { this.keywordTopK = keywordTopK; }
        public int getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
    }

    public static class Cache {
        private long embedTtlSeconds = 86400; // 24h
        private long chatTtlSeconds = 3600;   // 1h
        public long getEmbedTtlSeconds() { return embedTtlSeconds; }
        public void setEmbedTtlSeconds(long embedTtlSeconds) { this.embedTtlSeconds = embedTtlSeconds; }
        public long getChatTtlSeconds() { return chatTtlSeconds; }
        public void setChatTtlSeconds(long chatTtlSeconds) { this.chatTtlSeconds = chatTtlSeconds; }
    }

    public static class RateLimit {
        /** 单 provider 每分钟最多调用数。 */
        private int perMinute = 60;
        /** 熔断阈值：连续失败次数超过此值后暂时短路。 */
        private int circuitFailThreshold = 5;
        /** 熔断恢复：N 秒后尝试重新打开。 */
        private long circuitOpenSeconds = 30;
        public int getPerMinute() { return perMinute; }
        public void setPerMinute(int perMinute) { this.perMinute = perMinute; }
        public int getCircuitFailThreshold() { return circuitFailThreshold; }
        public void setCircuitFailThreshold(int circuitFailThreshold) { this.circuitFailThreshold = circuitFailThreshold; }
        public long getCircuitOpenSeconds() { return circuitOpenSeconds; }
        public void setCircuitOpenSeconds(long circuitOpenSeconds) { this.circuitOpenSeconds = circuitOpenSeconds; }
    }
}
