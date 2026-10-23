package com.mall.order.logistics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 物流配置（前缀 {@code mall.logistics}）。
 *
 * <p>{@code provider=manual|amap} 决定装配哪个 {@link LogisticsProvider} 实现；
 * 高德 Web 服务 Key 只从环境变量注入（{@code MALL_AMAP_WEB_KEY}），仓库不落密钥。</p>
 */
@Component
@ConfigurationProperties(prefix = "mall.logistics")
public class LogisticsProperties {

    /** 物流提供商：manual 手动（默认）/ amap 高德。 */
    private String provider = "manual";

    private final Amap amap = new Amap();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Amap getAmap() {
        return amap;
    }

    /** 高德 Web 服务配置。 */
    public static class Amap {

        /** Web 服务 Key（地理编码 + 驾车路径规划）。 */
        private String key = "";

        /** 高德 Web 服务根地址。 */
        private String baseUrl = "https://restapi.amap.com/v3";

        /** 单次调用超时（毫秒），超时按路线不可用降级。 */
        private int timeoutMs = 3000;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
