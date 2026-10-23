package com.mall.ai.vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的内存向量存储。
 *
 * <p>适用于商品 ≤1 万条的小规模场景，无需额外中间件；
 * 未来可抽 {@link VectorStore} 接口替换为 Milvus / Elasticsearch 等实现。</p>
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<Long, float[]> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(Long id, float[] vector) {
        if (id == null || vector == null || vector.length == 0) return;
        store.put(id, vector);
    }

    @Override
    public void bulkUpsert(Map<Long, float[]> entries) {
        if (entries == null) return;
        entries.forEach(this::upsert);
    }

    @Override
    public void remove(Long id) {
        if (id != null) store.remove(id);
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public Map<Long, Double> similarityTopK(float[] query, int k, Set<Long> excludeIds) {
        if (query == null || query.length == 0 || store.isEmpty()) return Map.of();
        // 排除 id
        Set<Long> exclude = excludeIds == null ? Set.of() : excludeIds;

        List<Map.Entry<Long, Double>> scored = new ArrayList<>(store.size());
        for (Map.Entry<Long, float[]> e : store.entrySet()) {
            if (exclude.contains(e.getKey())) continue;
            double sim = CosineSimilarity.compute(query, e.getValue());
            if (sim < 0) continue; // 零向量或维度不匹配跳过
            scored.add(Map.entry(e.getKey(), sim));
        }
        scored.sort(Map.Entry.<Long, Double>comparingByValue().reversed());

        int limit = Math.max(0, Math.min(k, scored.size()));
        Map<Long, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) result.put(scored.get(i).getKey(), scored.get(i).getValue());
        return result;
    }

    @Override
    public Optional<float[]> get(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    /** 当前向量数量。 */
    public int size() {
        return store.size();
    }
}
