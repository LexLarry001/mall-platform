package com.mall.product.product;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 商品详情聚合读模型缓存（plan Redis 分工：{@code mall:product:{spuId}}，Task 8 买家详情写入）。
 *
 * <p>商品变更（编辑/审核/上下架/删除）后统一失效，由下一次买家读取重建（RF5）。
 * Task 6/7 只负责删除，Task 8 负责写入。</p>
 */
@Service
public class ProductCacheService {

    /** 详情缓存键前缀。 */
    public static final String KEY_PREFIX = "mall:product:";

    private final StringRedisTemplate redis;

    public ProductCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 使指定 SPU 的详情缓存失效。 */
    public void evict(Long spuId) {
        redis.delete(KEY_PREFIX + spuId);
    }
}
