package com.mall.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 语义搜索相关配置（mall.ai.search.*）。 */
@Component
@ConfigurationProperties(prefix = "mall.ai.search")
public class AiSearchProperties {

    /** keyword / hybrid / auto。 */
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
