package com.mall.order.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 评价 AI 能力配置（mall.review.*），全部有默认值，不配置也能运行。 */
@Component
@ConfigurationProperties(prefix = "mall.review")
public class ReviewAiProperties {

    /** 生成 AI 摘要所需的最少评价数，不足走统计版。 */
    private int summaryMinCount = 3;

    /** 单次摘要最多送入模型的评价条数。 */
    private int summaryMaxReviews = 30;

    /** 摘要缓存天数（按 review_version 隔离，版本变化自然失效）。 */
    private long summaryCacheTtlMinutes = 10080;

    /** 推荐候选商品数。 */
    private int recommendCandidates = 12;

    /** 每个候选商品送入模型的评价条数。 */
    private int reviewsPerProduct = 3;

    /** 推荐结果缓存分钟数。 */
    private long recommendCacheTtlMinutes = 30;

    /** 全站每日 AI 推荐调用上限，超过后走规则推荐。 */
    private int dailyCallLimit = 500;

    /** 单用户每日推荐调用上限。 */
    private int userDailyLimit = 50;

    public int getSummaryMinCount() { return summaryMinCount; }
    public void setSummaryMinCount(int summaryMinCount) { this.summaryMinCount = summaryMinCount; }
    public int getSummaryMaxReviews() { return summaryMaxReviews; }
    public void setSummaryMaxReviews(int summaryMaxReviews) { this.summaryMaxReviews = summaryMaxReviews; }
    public long getSummaryCacheTtlMinutes() { return summaryCacheTtlMinutes; }
    public void setSummaryCacheTtlMinutes(long summaryCacheTtlMinutes) { this.summaryCacheTtlMinutes = summaryCacheTtlMinutes; }
    public int getRecommendCandidates() { return recommendCandidates; }
    public void setRecommendCandidates(int recommendCandidates) { this.recommendCandidates = recommendCandidates; }
    public int getReviewsPerProduct() { return reviewsPerProduct; }
    public void setReviewsPerProduct(int reviewsPerProduct) { this.reviewsPerProduct = reviewsPerProduct; }
    public long getRecommendCacheTtlMinutes() { return recommendCacheTtlMinutes; }
    public void setRecommendCacheTtlMinutes(long recommendCacheTtlMinutes) { this.recommendCacheTtlMinutes = recommendCacheTtlMinutes; }
    public int getDailyCallLimit() { return dailyCallLimit; }
    public void setDailyCallLimit(int dailyCallLimit) { this.dailyCallLimit = dailyCallLimit; }
    public int getUserDailyLimit() { return userDailyLimit; }
    public void setUserDailyLimit(int userDailyLimit) { this.userDailyLimit = userDailyLimit; }
}
