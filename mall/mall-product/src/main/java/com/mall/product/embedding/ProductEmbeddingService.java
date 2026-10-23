package com.mall.product.embedding;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.EmbeddingResult;
import com.mall.ai.gateway.AiService;
import com.mall.ai.vector.VectorStore;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.entity.ProductEmbeddingEntity;
import com.mall.product.entity.ProductEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductEmbeddingMapper;
import com.mall.product.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 商品向量服务：加载 / 生成 / 重建。
 *
 * <p>职责：启动时把已生成向量从 MySQL 加载到 {@link VectorStore}；
 * 商品发布/编辑后异步触发向量化，失败写 status=2 等待定时任务补偿。</p>
 */
@Service
public class ProductEmbeddingService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingService.class);
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    private final ProductEmbeddingMapper embeddingMapper;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final VectorStore vectorStore;
    private final AiService aiService;
    private final ObjectMapper objectMapper;

    public ProductEmbeddingService(ProductEmbeddingMapper embeddingMapper,
                                   ProductMapper productMapper,
                                   CategoryMapper categoryMapper,
                                   VectorStore vectorStore,
                                   AiService aiService,
                                   ObjectMapper objectMapper) {
        this.embeddingMapper = embeddingMapper;
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.vectorStore = vectorStore;
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    // =================== 启动加载 ===================

    @Override
    public void run(ApplicationArguments args) {
        if (!aiService.isEmbeddingEnabled()) {
            log.info("[AI] embedding disabled, skip product embedding load");
            return;
        }
        loadAllToVectorStore();
    }

    private void loadAllToVectorStore() {
        List<ProductEmbeddingEntity> all = embeddingMapper.selectList(
                Wrappers.<ProductEmbeddingEntity>lambdaQuery()
                        .eq(ProductEmbeddingEntity::getStatus, ProductEmbeddingEntity.STATUS_OK));
        Map<Long, float[]> loaded = new HashMap<>();
        for (ProductEmbeddingEntity e : all) {
            float[] vec = parseVector(e.getVector());
            if (vec != null) loaded.put(e.getSpuId(), vec);
        }
        vectorStore.bulkUpsert(loaded);
        log.info("[AI] loaded {} product embeddings into VectorStore", loaded.size());
    }

    // =================== 生成 / 更新 ===================

    /** 构建一个商品的向量（可能是首次生成，也可能是更新）。异步调用。 */
    @Async("mallAiExecutor")
    public void buildOne(Long spuId) {
        if (!aiService.isEmbeddingEnabled() || spuId == null) return;

        ProductEntity spu = productMapper.selectById(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != 2) {
            // 非已上架：删除向量（保持向量库与可见商品一致）
            vectorStore.remove(spuId);
            embeddingMapper.deleteById(spuId);
            return;
        }
        String text = buildEmbeddingText(spu);
        String newHash = sha256(text);

        // 文本没变且已有成功记录：跳过，节省调用
        ProductEmbeddingEntity existing = embeddingMapper.selectById(spuId);
        if (existing != null && ProductEmbeddingEntity.STATUS_OK == existing.getStatus()
                && newHash.equals(existing.getTextHash())) {
            return;
        }

        // 生成
        Optional<EmbeddingResult> result = aiService.embed(text);
        if (result.isEmpty()) {
            saveFail(spuId, existing, newHash, "AI 服务不可用");
            return;
        }
        float[] vec = result.get().vector();
        String jsonVec = vectorToJson(vec);
        ProductEmbeddingEntity entity = existing != null ? existing : new ProductEmbeddingEntity();
        entity.setSpuId(spuId);
        entity.setModel(result.get().model());
        entity.setDim(result.get().dim());
        entity.setVector(jsonVec);
        entity.setTextHash(newHash);
        entity.setStatus(ProductEmbeddingEntity.STATUS_OK);
        entity.setRetryCount(0);
        entity.setErrorMsg(null);
        if (existing != null) {
            embeddingMapper.updateById(entity);
        } else {
            embeddingMapper.insert(entity);
        }
        vectorStore.upsert(spuId, vec);
    }

    /** 全量重建（后台线程执行，避免管理端请求因逐条调用向量接口而超时）。 */
    @Async("mallAiExecutor")
    public void rebuildAllAsync() {
        if (!aiService.isEmbeddingEnabled()) {
            log.info("[AI] embedding disabled, skip rebuild");
            return;
        }
        int n = rebuildAll();
        log.info("[AI] rebuild all embeddings done: {} products", n);
    }

    /** 重建（平台管理员手动触发）。 */
    @Transactional
    public int rebuildAll() {
        List<ProductEntity> all = productMapper.selectList(
                Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getStatus, 2));
        int n = 0;
        for (ProductEntity p : all) {
            buildOne(p.getId());
            n++;
        }
        return n;
    }

    @Transactional
    public int rebuildByIds(List<Long> ids) {
        if (ids == null) return 0;
        for (Long id : ids) buildOne(id);
        return ids.size();
    }

    // =================== 工具 ===================

    /** 拼接向量化输入文本（控制在 512 token 内，简单版本）。 */
    public String buildEmbeddingText(ProductEntity spu) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(spu.getName())) sb.append(spu.getName());
        if (StringUtils.hasText(spu.getSubTitle())) sb.append(" ").append(spu.getSubTitle());
        if (spu.getCategoryId() != null) {
            List<String> path = buildCategoryPath(spu.getCategoryId());
            if (!path.isEmpty()) sb.append(" 类目：").append(String.join("/", path));
        }
        return sb.toString();
    }

    private List<String> buildCategoryPath(Long leafId) {
        if (leafId == null) return List.of();
        Map<Long, CategoryEntity> map = new HashMap<>();
        List<CategoryEntity> all = categoryMapper.selectList(null);
        for (CategoryEntity c : all) map.put(c.getId(), c);
        List<String> path = new ArrayList<>();
        Long cur = leafId;
        while (cur != null && map.containsKey(cur)) {
            CategoryEntity c = map.get(cur);
            path.add(0, c.getName());
            cur = c.getParentId();
        }
        return path;
    }

    private void saveFail(Long spuId, ProductEmbeddingEntity existing, String textHash, String msg) {
        ProductEmbeddingEntity entity = existing != null ? existing : new ProductEmbeddingEntity();
        entity.setSpuId(spuId);
        entity.setModel("unknown");
        entity.setDim(0);
        entity.setVector("[]");
        entity.setTextHash(textHash);
        entity.setStatus(ProductEmbeddingEntity.STATUS_FAIL);
        entity.setRetryCount((existing == null ? 0 : existing.getRetryCount()) + 1);
        entity.setErrorMsg(msg);
        if (existing != null) {
            embeddingMapper.updateById(entity);
        } else {
            embeddingMapper.insert(entity);
        }
        vectorStore.remove(spuId);
    }

    static float[] parseVector(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return STATIC_MAPPER.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String vectorToJson(float[] v) {
        try {
            return STATIC_MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s == null ? 0 : s.hashCode());
        }
    }
}
