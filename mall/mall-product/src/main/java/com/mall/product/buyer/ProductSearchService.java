package com.mall.product.buyer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.EmbeddingResult;
import com.mall.ai.gateway.AiService;
import com.mall.ai.vector.RrfFusion;
import com.mall.ai.vector.VectorStore;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.buyer.dto.ProductDetailVO;
import com.mall.product.buyer.dto.ProductSearchRequest;
import com.mall.product.config.AiSearchProperties;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ProductSkuEntity;
import com.mall.product.entity.ProductSpecEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ProductSkuMapper;
import com.mall.product.mapper.ProductSpecMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.ProductDetailDoc;
import com.mall.product.product.ProductCacheService;
import com.mall.product.product.dto.ProductListItemView;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 买家端商品搜索/详情（M2 + M8 语义搜索改造）。
 *
 * <p>M8 升级：搜索从纯关键词（LIKE）升级为"关键词 + 向量"混合召回 + RRF 融合；
 * 通过 {@link AiSearchProperties} 控制模式（keyword / hybrid / auto），
 * 默认 {@code keyword} 保持原有行为不变。</p>
 */
@Service
public class ProductSearchService {

    /** 热搜词 ZSet 键。 */
    public static final String HOT_SEARCH_KEY = "mall:hot:search";

    /** 商品状态：已上架（买家可见的唯一状态）。 */
    private static final int STATUS_ON_SHELF = 2;
    private static final int CATEGORY_STATUS_ENABLED = 1;
    private static final Duration DETAIL_CACHE_TTL = Duration.ofMinutes(30);
    private static final Set<String> SUPPORTED_SORTS =
            Set.of("default", "sales", "price_asc", "price_desc", "new");

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductSpecMapper productSpecMapper;
    private final CategoryMapper categoryMapper;
    private final ShopMapper shopMapper;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // M8 新增
    private final AiService aiService;
    private final VectorStore vectorStore;
    private final AiSearchProperties searchProps;

    public ProductSearchService(ProductMapper productMapper, ProductSkuMapper productSkuMapper,
                                ProductSpecMapper productSpecMapper, CategoryMapper categoryMapper,
                                ShopMapper shopMapper, MongoTemplate mongoTemplate,
                                StringRedisTemplate redis, ObjectMapper objectMapper,
                                AiService aiService, VectorStore vectorStore,
                                AiSearchProperties searchProps) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.productSpecMapper = productSpecMapper;
        this.categoryMapper = categoryMapper;
        this.shopMapper = shopMapper;
        this.mongoTemplate = mongoTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.aiService = aiService;
        this.vectorStore = vectorStore;
        this.searchProps = searchProps;
    }

    // =================== M8 混合检索 ===================

    /**
     * 搜索已上架商品：关键词（字面）/类目（含启用子孙）/店铺过滤 + 排序 + 分页。
     *
     * <p>M8 新增：当 mode=hybrid 且有关键词时，叠加向量召回 + RRF 融合排序。</p>
     */
    public Page<ProductListItemView> search(ProductSearchRequest request) {
        String sort = StringUtils.hasText(request.sort()) ? request.sort() : "default";
        if (!SUPPORTED_SORTS.contains(sort)) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "不支持的排序方式");
        }
        String keyword = request.keyword() == null ? null : request.keyword().trim();
        String mode = searchProps.getMode();

        // 1. 关键词召回
        List<Long> keywordIds = keywordRecall(request, searchProps.getKeywordTopK());

        // 2. 向量召回（仅 hybrid + 有关键词 + 向量能力可用）
        List<Long> vectorIds = List.of();
        if (("hybrid".equals(mode) || "auto".equals(mode))
                && StringUtils.hasText(keyword)
                && aiService.isEmbeddingEnabled()) {
            vectorIds = vectorRecall(keyword, searchProps.getVectorTopK());
        }

        // 3. 融合
        List<Long> fused = fuse(keywordIds, vectorIds);

        // 4. 回表 + 过滤 + 排序 + 分页
        Page<ProductEntity> entityPage = fetchFilterSortPage(fused, request, sort);
        Page<ProductListItemView> viewPage = new Page<>(entityPage.getCurrent(),
                entityPage.getSize(), entityPage.getTotal());
        viewPage.setRecords(entityPage.getRecords().stream().map(ProductListItemView::from).toList());

        if (StringUtils.hasText(keyword)) {
            redis.opsForZSet().incrementScore(HOT_SEARCH_KEY, keyword, 1.0);
        }
        return viewPage;
    }

    private List<Long> keywordRecall(ProductSearchRequest request, int limit) {
        LambdaQueryWrapper<ProductEntity> query = Wrappers.<ProductEntity>lambdaQuery()
                .eq(ProductEntity::getStatus, STATUS_ON_SHELF)
                .like(StringUtils.hasText(request.keyword()),
                        ProductEntity::getName, request.keyword())
                .eq(request.shopId() != null, ProductEntity::getShopId, request.shopId())
                .last("LIMIT " + limit);
        if (request.categoryId() != null) {
            query.in(ProductEntity::getCategoryId, collectEnabledCategoryIds(request.categoryId()));
        }
        return productMapper.selectList(query).stream()
                .map(ProductEntity::getId).toList();
    }

    private List<Long> vectorRecall(String keyword, int limit) {
        Optional<EmbeddingResult> emb = aiService.embed(keyword);
        if (emb.isEmpty()) return List.of(); // AI 不可用时静默降级
        Map<Long, Double> scores = vectorStore.similarityTopK(emb.get().vector(), limit, null);
        return new ArrayList<>(scores.keySet());
    }

    private List<Long> fuse(List<Long> keywordIds, List<Long> vectorIds) {
        Map<Long, Double> result = RrfFusion.fuse(List.of(keywordIds, vectorIds), searchProps.getRrfK());
        // 过滤掉分数为 0 的（理论上不会出现，但防御性）
        return result.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey).toList();
    }

    /**
     * 回表 + 过滤 + 排序 + 分页。
     *
     * <p>先把 fused 全集查出（status 已在 recall 时过滤），再套用 shopId/categoryId，
     * 最后按 sort 排序 + 分页，保证融合结果优先。</p>
     */
    private Page<ProductEntity> fetchFilterSortPage(List<Long> fused, ProductSearchRequest request,
                                                    String sort) {
        LambdaQueryWrapper<ProductEntity> query = Wrappers.<ProductEntity>lambdaQuery()
                .eq(ProductEntity::getStatus, STATUS_ON_SHELF);

        if (fused == null || fused.isEmpty()) {
            // 没融合结果时退回纯关键词查询（不设 IN idList，由 LIKE/类目/店铺过滤兜底）
            query.like(StringUtils.hasText(request.keyword()),
                    ProductEntity::getName, request.keyword())
                    .eq(request.shopId() != null, ProductEntity::getShopId, request.shopId());
            if (request.categoryId() != null) {
                query.in(ProductEntity::getCategoryId, collectEnabledCategoryIds(request.categoryId()));
            }
            applySort(query, sort);
            return productMapper.selectPage(new Page<>(request.page(), request.size()), query);
        }

        // 有融合结果：先 IN fused 再套用 shopId/categoryId
        query.in(ProductEntity::getId, fused)
                .eq(request.shopId() != null, ProductEntity::getShopId, request.shopId());
        if (request.categoryId() != null) {
            query.in(ProductEntity::getCategoryId, collectEnabledCategoryIds(request.categoryId()));
        }
        applySort(query, sort);
        return productMapper.selectPage(new Page<>(request.page(), request.size()), query);
    }

    private void applySort(LambdaQueryWrapper<ProductEntity> query, String sort) {
        switch (sort) {
            case "sales" -> query.orderByDesc(ProductEntity::getSales);
            case "price_asc" -> query.orderByAsc(ProductEntity::getPrice);
            case "price_desc" -> query.orderByDesc(ProductEntity::getPrice);
            case "new" -> query.orderByDesc(ProductEntity::getCreateTime);
            default -> query.orderByDesc(ProductEntity::getUpdateTime);
        }
    }

    // =================== 原有方法保持不变 ===================

    /** 热搜词 Top10（按分数降序）。 */
    public List<String> hotSearch() {
        Set<String> top = redis.opsForZSet().reverseRange(HOT_SEARCH_KEY, 0, 9);
        return top == null ? List.of() : List.copyOf(top);
    }

    /**
     * 商品详情聚合：先读 {@code mall:product:{spuId}} 缓存，miss 组装回写（TTL 30 分钟）。
     * 非上架状态抛"商品不存在或已下架"（RF3），错误结果不缓存。
     */
    public ProductDetailVO detail(Long spuId) {
        String cacheKey = ProductCacheService.KEY_PREFIX + spuId;
        String cached = redis.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                return objectMapper.readValue(cached, ProductDetailVO.class);
            } catch (JsonProcessingException e) {
                // 缓存损坏按 miss 处理
            }
        }
        ProductDetailVO vo = assembleDetail(spuId);
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(vo), DETAIL_CACHE_TTL);
        } catch (JsonProcessingException ignored) {
        }
        return vo;
    }

    /**
     * 收集目标类目及其全部启用子孙类目 id（BFS）。
     * 停用类目及其子树不纳入；目标类目本身无论启停都保留（调用方显式指定）。
     */
    List<Long> collectEnabledCategoryIds(Long categoryId) {
        List<CategoryEntity> enabled = categoryMapper.selectList(Wrappers.<CategoryEntity>lambdaQuery()
                .eq(CategoryEntity::getStatus, CATEGORY_STATUS_ENABLED));
        Map<Long, List<Long>> childrenByParent = enabled.stream()
                .collect(Collectors.groupingBy(CategoryEntity::getParentId,
                        Collectors.mapping(CategoryEntity::getId, Collectors.toList())));
        Set<Long> ids = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (ids.add(current)) {
                queue.addAll(childrenByParent.getOrDefault(current, List.of()));
            }
        }
        return List.copyOf(ids);
    }

    private ProductDetailVO assembleDetail(Long spuId) {
        ProductEntity spu = productMapper.selectById(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != STATUS_ON_SHELF) {
            throw new BizException("商品不存在或已下架");
        }
        List<ProductDetailVO.SpecView> specs = productSpecMapper.selectList(
                        Wrappers.<ProductSpecEntity>lambdaQuery().eq(ProductSpecEntity::getSpuId, spuId))
                .stream()
                .map(spec -> new ProductDetailVO.SpecView(spec.getSpecName(),
                        parseStringList(spec.getSpecValues())))
                .toList();
        List<ProductDetailVO.SkuView> skus = productSkuMapper.selectList(
                        Wrappers.<ProductSkuEntity>lambdaQuery().eq(ProductSkuEntity::getSpuId, spuId))
                .stream()
                .map(sku -> new ProductDetailVO.SkuView(sku.getId(), parseSpecJson(sku.getSpecJson()),
                        sku.getPrice(), sku.getStock(), sku.getSkuImg()))
                .toList();
        ProductDetailDoc doc = mongoTemplate.findOne(
                Query.query(Criteria.where("spuId").is(spuId)), ProductDetailDoc.class);
        ShopEntity shop = spu.getShopId() == null ? null : shopMapper.selectById(spu.getShopId());
        ProductDetailVO.ShopView shopView = shop == null ? null
                : new ProductDetailVO.ShopView(shop.getId(), shop.getName(), shop.getLogo(),
                shop.getIntro(), shop.getBizStatus());
        ProductDetailVO.SpuView spuView = new ProductDetailVO.SpuView(spu.getId(), spu.getShopId(),
                spu.getCategoryId(), spu.getName(), spu.getSubTitle(), spu.getMainImg(),
                splitGallery(spu.getImgList()), spu.getPrice(), spu.getSales(),
                spu.getAvgRating(), spu.getCommentCount());
        return new ProductDetailVO(spuView, skus, specs, doc == null ? null : doc.getHtml(), shopView);
    }

    private List<String> splitGallery(String imgList) {
        return StringUtils.hasText(imgList) ? List.of(imgList.split(",")) : List.of();
    }

    private List<String> parseStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Map<String, String> parseSpecJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
