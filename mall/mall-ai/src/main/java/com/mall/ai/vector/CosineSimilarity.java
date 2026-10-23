package com.mall.ai.vector;

/**
 * 余弦相似度（纯静态数学工具，不依赖 Spring）。
 *
 * <p>两个归一化向量的余弦值即为点积；未归一化向量需先算模长。
 * 维度不一致返回 {@code -1.0}（视为完全不相似，由上层处理）。
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    public static double compute(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return -1.0; // 零向量保护
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 判断两个向量是否已经归一化（L2 范数 ≈ 1，容差 0.01）。 */
    public static boolean isNormalized(float[] v) {
        if (v == null || v.length == 0) return false;
        double sum = 0.0;
        for (float f : v) sum += (double) f * f;
        return Math.abs(Math.sqrt(sum) - 1.0) < 0.01;
    }
}
