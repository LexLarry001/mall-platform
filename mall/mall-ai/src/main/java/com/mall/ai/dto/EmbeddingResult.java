package com.mall.ai.dto;

import java.util.List;

/** Embedding 结果：向量 + 维度 + 模型名。 */
public record EmbeddingResult(
        float[] vector,
        int dim,
        String model) {

    /** 归一化：L2 范数 = 1。用于让余弦相似度计算正确。 */
    public EmbeddingResult normalize() {
        if (vector == null || vector.length == 0) return this;
        double sum = 0.0;
        for (float v : vector) sum += (double) v * v;
        if (sum == 0.0) return this; // 零向量不做归一化
        double inv = 1.0 / Math.sqrt(sum);
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) out[i] = (float) (vector[i] * inv);
        return new EmbeddingResult(out, dim, model);
    }

    public static EmbeddingResult of(float[] vector, String model) {
        return new EmbeddingResult(vector, vector.length, model);
    }
}
