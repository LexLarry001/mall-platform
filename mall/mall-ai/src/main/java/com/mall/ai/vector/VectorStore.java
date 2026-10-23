package com.mall.ai.vector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 向量存储接口：抽象为可替换的 VectorStore。
 *
 * <p>当前实现为 {@link InMemoryVectorStore}（应用内存余弦，适用于 ≤1 万条小规模）；
 * 未来可替换为 Milvus / Elasticsearch / pgvector 实现，业务代码无需改动。</p>
 */
public interface VectorStore {

    /** Upsert 单条。 */
    void upsert(Long id, float[] vector);

    /** 批量 upsert。 */
    void bulkUpsert(Map<Long, float[]> entries);

    /** 删除。 */
    void remove(Long id);

    /** 清空。 */
    void clear();

    /** TopK 近似最近邻（近似=此处为暴力计算，数据量小时等价）。 */
    Map<Long, Double> similarityTopK(float[] query, int k, Set<Long> excludeIds);

    /** 按 id 取向量（可选）。 */
    Optional<float[]> get(Long id);
}
