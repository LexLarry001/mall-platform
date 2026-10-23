package com.mall.product.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ProductSkuEntity;
import com.mall.product.entity.ProductSpecEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ProductSkuMapper;
import com.mall.product.mapper.ProductSpecMapper;
import com.mall.product.mongo.ProductDetailDoc;
import com.mall.product.product.dto.MerchantProductDetailView;
import com.mall.product.product.dto.ProductListItemView;
import com.mall.product.product.dto.ProductSaveRequest;
import com.mall.product.embedding.ProductChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 商家商品发布/编辑/上下架（plan Task 6）。
 *
 * <p>数据级鉴权：shopId 由 Controller 从登录态取（{@code SecurityUtils.getRequiredShopId()}），
 * 本服务内所有读写按 shopId 限定本店；操作他店商品一律按"商品不存在"拒绝且不改数据（RF1）。</p>
 *
 * <p>状态机（plan RF3）：发布/任何编辑 → 1 待审核；仅 2 已上架可被商家下架（→4）；
 * 仅 4 可被商家重新上架（→2）；5 平台违规下架的商品商家不可恢复；仅 0/3/4 可删除。</p>
 *
 * <p>一致性：SPU/规格/SKU 写 MySQL 本地事务；富文本详情随写操作写 MongoDB
 * {@code product_detail}（与入驻审核留痕同事务口径一致）；任何变更后删除
 * {@code mall:product:{spuId}} 详情缓存（RF5）。</p>
 */
@Service
public class MerchantProductService {

    /** 状态机常量。 */
    private static final int STATUS_PENDING = 1;
    private static final int STATUS_ON_SHELF = 2;
    private static final int STATUS_REJECTED = 3;
    private static final int STATUS_OFF = 4;
    private static final int STATUS_BANNED = 5;
    private static final int STATUS_DRAFT = 0;

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductSpecMapper productSpecMapper;
    private final CategoryMapper categoryMapper;
    private final MongoTemplate mongoTemplate;
    private final ProductCacheService productCacheService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public MerchantProductService(ProductMapper productMapper, ProductSkuMapper productSkuMapper,
                                  ProductSpecMapper productSpecMapper, CategoryMapper categoryMapper,
                                  MongoTemplate mongoTemplate, ProductCacheService productCacheService,
                                  ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
        this.productSpecMapper = productSpecMapper;
        this.categoryMapper = categoryMapper;
        this.mongoTemplate = mongoTemplate;
        this.productCacheService = productCacheService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布商品：SPU（status=1 待审核，price=SKU 最低价）+ 规格 + SKU 同事务落库，
     * 富文本详情写 MongoDB，详情缓存失效。
     */
    @Transactional
    public Long create(Long shopId, ProductSaveRequest request) {
        validateSkus(request.skus());
        requireEnabledCategory(request.categoryId());

        ProductEntity spu = new ProductEntity();
        spu.setShopId(shopId);
        spu.setCategoryId(request.categoryId());
        spu.setName(request.name().trim());
        spu.setSubTitle(request.subTitle());
        spu.setMainImg(request.mainImg());
        spu.setImgList(joinGallery(request.imgList()));
        spu.setPrice(minPrice(request.skus()));
        spu.setSales(0);
        spu.setCommentCount(0);
        spu.setStatus(STATUS_PENDING);
        productMapper.insert(spu);

        insertChildren(spu.getId(), request);
        saveDetail(spu.getId(), request.detailHtml(), request.imgList());
        productCacheService.evict(spu.getId());
        eventPublisher.publishEvent(new ProductChangedEvent(spu.getId()));
        return spu.getId();
    }

    /**
     * 编辑商品：仅本店（RF1）；任何编辑都回到 1 待审核（RF3，原已上架商品买家端
     * 立即不可见），审核备注清空；规格/SKU 整批替换；Mongo 详情更新；缓存失效。
     */
    @Transactional
    public void update(Long shopId, Long spuId, ProductSaveRequest request) {
        ProductEntity spu = requireOwnProduct(shopId, spuId);
        validateSkus(request.skus());
        requireEnabledCategory(request.categoryId());

        spu.setCategoryId(request.categoryId());
        spu.setName(request.name().trim());
        spu.setSubTitle(request.subTitle());
        spu.setMainImg(request.mainImg());
        spu.setImgList(joinGallery(request.imgList()));
        spu.setPrice(minPrice(request.skus()));
        spu.setStatus(STATUS_PENDING);
        spu.setAuditRemark(null);
        productMapper.updateById(spu);

        productSkuMapper.delete(Wrappers.<ProductSkuEntity>lambdaQuery()
                .eq(ProductSkuEntity::getSpuId, spuId));
        productSpecMapper.delete(Wrappers.<ProductSpecEntity>lambdaQuery()
                .eq(ProductSpecEntity::getSpuId, spuId));
        insertChildren(spuId, request);

        saveDetail(spuId, request.detailHtml(), request.imgList());
        productCacheService.evict(spuId);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /** 本店商品分页：状态/名称关键词过滤，按更新时间倒序。 */
    public Page<ProductListItemView> list(Long shopId, Integer status, String keyword,
                                          long pageNum, long pageSize) {
        Page<ProductEntity> entityPage = productMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ProductEntity>lambdaQuery()
                        .eq(ProductEntity::getShopId, shopId)
                        .eq(status != null, ProductEntity::getStatus, status)
                        .like(StringUtils.hasText(keyword), ProductEntity::getName, keyword)
                        .orderByDesc(ProductEntity::getUpdateTime));
        Page<ProductListItemView> viewPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(),
                entityPage.getTotal());
        viewPage.setRecords(entityPage.getRecords().stream().map(ProductListItemView::from).toList());
        return viewPage;
    }

    /** 本店商品详情回显（编辑页）：SPU + 规格 + SKU + Mongo 富文本。 */
    public MerchantProductDetailView detail(Long shopId, Long spuId) {
        ProductEntity spu = requireOwnProduct(shopId, spuId);
        List<MerchantProductDetailView.SpecView> specs = productSpecMapper.selectList(
                        Wrappers.<ProductSpecEntity>lambdaQuery().eq(ProductSpecEntity::getSpuId, spuId))
                .stream()
                .map(spec -> new MerchantProductDetailView.SpecView(spec.getSpecName(),
                        parseStringList(spec.getSpecValues())))
                .toList();
        List<MerchantProductDetailView.SkuView> skus = productSkuMapper.selectList(
                        Wrappers.<ProductSkuEntity>lambdaQuery().eq(ProductSkuEntity::getSpuId, spuId))
                .stream()
                .map(sku -> new MerchantProductDetailView.SkuView(sku.getId(),
                        parseSpecJson(sku.getSpecJson()), sku.getPrice(), sku.getStock(), sku.getSkuImg()))
                .toList();
        ProductDetailDoc doc = mongoTemplate.findOne(
                Query.query(Criteria.where("spuId").is(spuId)), ProductDetailDoc.class);
        return new MerchantProductDetailView(spu.getId(), spu.getCategoryId(), spu.getName(),
                spu.getSubTitle(), spu.getMainImg(), splitGallery(spu.getImgList()),
                spu.getStatus(), spu.getAuditRemark(), specs, skus,
                doc == null ? null : doc.getHtml());
    }

    /** 商家下架：仅已上架（2）→ 4。 */
    @Transactional
    public void off(Long shopId, Long spuId) {
        ProductEntity spu = requireOwnProduct(shopId, spuId);
        if (spu.getStatus() != STATUS_ON_SHELF) {
            throw new BizException("仅已上架商品可下架");
        }
        spu.setStatus(STATUS_OFF);
        productMapper.updateById(spu);
        productCacheService.evict(spuId);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /** 商家重新上架：仅商家下架（4）→ 2；平台违规下架（5）拒绝（RF3）。 */
    @Transactional
    public void on(Long shopId, Long spuId) {
        ProductEntity spu = requireOwnProduct(shopId, spuId);
        if (spu.getStatus() == STATUS_BANNED) {
            throw new BizException("商品已被平台下架，暂不能上架");
        }
        if (spu.getStatus() != STATUS_OFF) {
            throw new BizException("仅已下架商品可重新上架");
        }
        spu.setStatus(STATUS_ON_SHELF);
        productMapper.updateById(spu);
        productCacheService.evict(spuId);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /** 删除（逻辑删除）：仅 0 草稿 / 3 驳回 / 4 商家下架可删；级联 SKU/规格/Mongo 详情。 */
    @Transactional
    public void delete(Long shopId, Long spuId) {
        ProductEntity spu = requireOwnProduct(shopId, spuId);
        int status = spu.getStatus();
        if (status != STATUS_DRAFT && status != STATUS_REJECTED && status != STATUS_OFF) {
            throw new BizException("当前状态的商品不可删除");
        }
        productMapper.deleteById(spuId);
        productSkuMapper.delete(Wrappers.<ProductSkuEntity>lambdaQuery()
                .eq(ProductSkuEntity::getSpuId, spuId));
        productSpecMapper.delete(Wrappers.<ProductSpecEntity>lambdaQuery()
                .eq(ProductSpecEntity::getSpuId, spuId));
        mongoTemplate.remove(Query.query(Criteria.where("spuId").is(spuId)), ProductDetailDoc.class);
        productCacheService.evict(spuId);
        eventPublisher.publishEvent(new ProductChangedEvent(spuId));
    }

    /** 发布/编辑的基础校验：SKU 非空、价格与库存非负、规格组合不重复（与键序无关）。 */
    private void validateSkus(List<ProductSaveRequest.SkuDef> skus) {
        if (skus == null || skus.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID.getCode(), "SKU 至少一条");
        }
        Set<String> combos = new HashSet<>();
        for (ProductSaveRequest.SkuDef sku : skus) {
            if (sku.price() == null || sku.price().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(ResultCode.PARAM_INVALID.getCode(), "SKU 价格不能为负");
            }
            if (sku.stock() == null || sku.stock() < 0) {
                throw new BizException(ResultCode.PARAM_INVALID.getCode(), "SKU 库存不能为负");
            }
            if (sku.specValues() == null || sku.specValues().isEmpty()) {
                throw new BizException(ResultCode.PARAM_INVALID.getCode(), "SKU 规格组合不能为空");
            }
            if (!combos.add(canonicalCombo(sku.specValues()))) {
                throw new BizException("SKU 规格组合重复，请检查");
            }
        }
    }

    /** 规格组合规范化：按规格名排序后序列化，保证与前端传的键序无关。 */
    private String canonicalCombo(Map<String, String> specValues) {
        return new TreeMap<>(specValues).toString();
    }

    private void requireEnabledCategory(Long categoryId) {
        CategoryEntity category = categoryId == null ? null : categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BizException("类目不存在");
        }
        if (category.getStatus() == null || category.getStatus() != 1) {
            throw new BizException("类目已停用，不可发布商品");
        }
    }

    /** 本店商品：不存在或不属于本店统一按"商品不存在"拒绝（RF1，不泄漏他店数据存在性）。 */
    private ProductEntity requireOwnProduct(Long shopId, Long spuId) {
        ProductEntity spu = productMapper.selectById(spuId);
        if (spu == null || !spu.getShopId().equals(shopId)) {
            throw new BizException("商品不存在");
        }
        return spu;
    }

    private void insertChildren(Long spuId, ProductSaveRequest request) {
        if (request.specs() != null) {
            for (ProductSaveRequest.SpecDef spec : request.specs()) {
                ProductSpecEntity entity = new ProductSpecEntity();
                entity.setSpuId(spuId);
                entity.setSpecName(spec.specName());
                entity.setSpecValues(writeJson(spec.values()));
                productSpecMapper.insert(entity);
            }
        }
        for (ProductSaveRequest.SkuDef sku : request.skus()) {
            ProductSkuEntity entity = new ProductSkuEntity();
            entity.setSpuId(spuId);
            entity.setSpecJson(writeJson(sku.specValues()));
            entity.setPrice(sku.price());
            entity.setStock(sku.stock());
            entity.setSkuImg(sku.skuImg());
            productSkuMapper.insert(entity);
        }
    }

    /** Mongo 详情整篇替换：已存在则保留文档主键覆盖，否则新建（spuId 唯一索引）。 */
    private void saveDetail(Long spuId, String html, List<String> gallery) {
        ProductDetailDoc doc = mongoTemplate.findOne(
                Query.query(Criteria.where("spuId").is(spuId)), ProductDetailDoc.class);
        if (doc == null) {
            doc = new ProductDetailDoc();
            doc.setSpuId(spuId);
        }
        doc.setHtml(html);
        doc.setGallery(gallery == null ? List.of() : List.copyOf(gallery));
        doc.setUpdateTime(LocalDateTime.now());
        mongoTemplate.save(doc);
    }

    private BigDecimal minPrice(List<ProductSaveRequest.SkuDef> skus) {
        return skus.stream().map(ProductSaveRequest.SkuDef::price).min(BigDecimal::compareTo).orElseThrow();
    }

    private String joinGallery(List<String> imgList) {
        return imgList == null || imgList.isEmpty() ? null : String.join(",", imgList);
    }

    private List<String> splitGallery(String imgList) {
        return StringUtils.hasText(imgList) ? List.of(imgList.split(",")) : List.of();
    }

    private List<String> parseStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Map<String, String> parseSpecJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR.getCode(), "规格数据序列化失败");
        }
    }
}
