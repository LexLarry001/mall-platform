package com.mall.ai.vector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合排序。
 *
 * <p>不依赖两路分数同量纲，只看排名，工程上最省事。</p>
 *
 * @param k 平滑参数，经验值 60，让排名靠前的文档优势平滑衰减
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    public static Map<Long, Double> fuse(List<Long>... rankLists) {
        return fuse(List.of(rankLists), 60);
    }

    public static Map<Long, Double> fuse(List<List<Long>> rankLists, int k) {
        Map<Long, Double> score = new LinkedHashMap<>();
        for (List<Long> list : rankLists) {
            if (list == null) continue;
            for (int i = 0; i < list.size(); i++) {
                score.merge(list.get(i), 1.0 / (k + i), Double::sum);
            }
        }
        // 按分数降序
        Map<Long, Double> result = new LinkedHashMap<>();
        score.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .forEachOrdered(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }
}
