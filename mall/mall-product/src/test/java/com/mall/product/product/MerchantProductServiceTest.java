package com.mall.product.product;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家商品发布/编辑/上下架单测（plan Task 6 Step 1）。
 *
 * <p>钉死项：RF1 跨店越权、RF3 编辑回待审核与平台下架禁上架、RF5 详情缓存失效、
 * 发布校验（空 SKU/负价格库存/重复规格组合/类目缺失停用）、Mongo 详情随发布写入。</p>
 */
@ExtendWith(MockitoExtension.class)
class MerchantProductServiceTest {

    private static final Long SHOP_ID = 7L;
    private static final Long SPU_ID = 999L;

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private ProductSpecMapper productSpecMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private ProductCacheService productCacheService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MerchantProductService productService;

    @BeforeEach
    void setUp() {
        productService = new MerchantProductService(productMapper, productSkuMapper, productSpecMapper,
                categoryMapper, mongoTemplate, productCacheService, new ObjectMapper(), eventPublisher);
    }

    /** 发布：SPU 状态=待审核、price=SKU 最低价；规格/SKU/图集落库；Mongo 详情写入；缓存键删除。 */
    @Test
    void create_persists_spu_specs_skus_and_mongo_detail() {
        when(categoryMapper.selectById(101L)).thenReturn(category(101L, 1));
        when(productMapper.insert(any(ProductEntity.class))).thenAnswer(invocation -> {
            ProductEntity entity = invocation.getArgument(0);
            entity.setId(SPU_ID);
            return 1;
        });

        Long spuId = productService.create(SHOP_ID, saveRequest());

        assertThat(spuId).isEqualTo(SPU_ID);
        ArgumentCaptor<ProductEntity> spuCaptor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).insert(spuCaptor.capture());
        ProductEntity spu = spuCaptor.getValue();
        assertThat(spu.getShopId()).isEqualTo(SHOP_ID);
        assertThat(spu.getStatus()).isEqualTo(1);
        assertThat(spu.getPrice()).isEqualByComparingTo("179.00");
        assertThat(spu.getImgList()).isEqualTo("/static/a.png,/static/b.png");
        assertThat(spu.getSales()).isZero();
        assertThat(spu.getCommentCount()).isZero();

        ArgumentCaptor<ProductSpecEntity> specCaptor = ArgumentCaptor.forClass(ProductSpecEntity.class);
        verify(productSpecMapper, times(1)).insert(specCaptor.capture());
        assertThat(specCaptor.getValue().getSpuId()).isEqualTo(SPU_ID);
        assertThat(specCaptor.getValue().getSpecName()).isEqualTo("颜色");

        ArgumentCaptor<ProductSkuEntity> skuCaptor = ArgumentCaptor.forClass(ProductSkuEntity.class);
        verify(productSkuMapper, times(2)).insert(skuCaptor.capture());
        assertThat(skuCaptor.getAllValues()).allSatisfy(sku -> {
            assertThat(sku.getSpuId()).isEqualTo(SPU_ID);
            assertThat(sku.getSpecJson()).contains("颜色");
        });

        ArgumentCaptor<ProductDetailDoc> docCaptor = ArgumentCaptor.forClass(ProductDetailDoc.class);
        verify(mongoTemplate).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getSpuId()).isEqualTo(SPU_ID);
        assertThat(docCaptor.getValue().getHtml()).isEqualTo("<p>详情</p>");
        assertThat(docCaptor.getValue().getGallery()).containsExactly("/static/a.png", "/static/b.png");

        verify(productCacheService).evict(SPU_ID);
    }

    /** 发布校验：SKU 为空 → 业务错误，不触库。 */
    @Test
    void create_rejects_empty_skus() {
        ProductSaveRequest request = new ProductSaveRequest(101L, "商品", null, "/static/a.png",
                null, null, List.of(), null);

        assertThatThrownBy(() -> productService.create(SHOP_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SKU");
        verify(productMapper, never()).insert(any(ProductEntity.class));
    }

    /** 发布校验：负价格 / 负库存 → 业务错误。 */
    @Test
    void create_rejects_negative_price_or_stock() {
        ProductSaveRequest negativePrice = new ProductSaveRequest(101L, "商品", null, "/static/a.png",
                null, null, List.of(sku("-0.01", 1)), null);
        assertThatThrownBy(() -> productService.create(SHOP_ID, negativePrice))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("价格");

        ProductSaveRequest negativeStock = new ProductSaveRequest(101L, "商品", null, "/static/a.png",
                null, null, List.of(sku("9.90", -1)), null);
        assertThatThrownBy(() -> productService.create(SHOP_ID, negativeStock))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存");

        verify(productMapper, never()).insert(any(ProductEntity.class));
    }

    /** 发布校验：规格组合重复（与键顺序无关）→ 业务错误。 */
    @Test
    void create_rejects_duplicate_spec_combos() {
        Map<String, String> combo1 = new LinkedHashMap<>();
        combo1.put("颜色", "黑");
        combo1.put("容量", "256G");
        Map<String, String> combo2 = new LinkedHashMap<>();
        combo2.put("容量", "256G");
        combo2.put("颜色", "黑");
        ProductSaveRequest request = new ProductSaveRequest(101L, "商品", null, "/static/a.png",
                null, null,
                List.of(new ProductSaveRequest.SkuDef(combo1, new BigDecimal("9.90"), 1, null),
                        new ProductSaveRequest.SkuDef(combo2, new BigDecimal("9.90"), 1, null)),
                null);

        assertThatThrownBy(() -> productService.create(SHOP_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复");
        verify(productMapper, never()).insert(any(ProductEntity.class));
    }

    /** 发布校验：类目不存在或已停用 → 业务错误。 */
    @Test
    void create_rejects_missing_or_disabled_category() {
        when(categoryMapper.selectById(101L)).thenReturn(null);
        assertThatThrownBy(() -> productService.create(SHOP_ID, saveRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类目");

        when(categoryMapper.selectById(101L)).thenReturn(category(101L, 0));
        assertThatThrownBy(() -> productService.create(SHOP_ID, saveRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("停用");

        verify(productMapper, never()).insert(any(ProductEntity.class));
    }

    /** RF1：编辑/操作他店商品 → 业务错误且数据不变。 */
    @Test
    void update_rejects_other_shops_product() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, 8L, 2));

        assertThatThrownBy(() -> productService.update(SHOP_ID, SPU_ID, saveRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        verify(productMapper, never()).updateById(any(ProductEntity.class));
        verify(mongoTemplate, never()).save(any(ProductDetailDoc.class));
        verify(productCacheService, never()).evict(anyLong());
    }

    /** RF3/RF5：编辑已上架商品 → status 回待审核、审核备注清空、子表重建、Mongo 详情更新、缓存删除。 */
    @Test
    void update_resets_status_to_pending_and_replaces_children() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 2));
        when(categoryMapper.selectById(101L)).thenReturn(category(101L, 1));
        ProductDetailDoc existing = new ProductDetailDoc();
        existing.setId("mongo-id-1");
        existing.setSpuId(SPU_ID);
        when(mongoTemplate.findOne(any(Query.class), eq(ProductDetailDoc.class))).thenReturn(existing);

        productService.update(SHOP_ID, SPU_ID, saveRequest());

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        ProductEntity updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(1);
        assertThat(updated.getAuditRemark()).isNull();
        assertThat(updated.getPrice()).isEqualByComparingTo("179.00");

        verify(productSkuMapper).delete(any(Wrapper.class));
        verify(productSpecMapper).delete(any(Wrapper.class));
        verify(productSkuMapper, times(2)).insert(any(ProductSkuEntity.class));

        ArgumentCaptor<ProductDetailDoc> docCaptor = ArgumentCaptor.forClass(ProductDetailDoc.class);
        verify(mongoTemplate).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getId()).isEqualTo("mongo-id-1");
        assertThat(docCaptor.getValue().getHtml()).isEqualTo("<p>详情</p>");

        verify(productCacheService).evict(SPU_ID);
    }

    /** 商家下架：仅已上架（2）可下架；下架后缓存删除。 */
    @Test
    void off_requires_on_shelf_status() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 1));
        assertThatThrownBy(() -> productService.off(SHOP_ID, SPU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已上架");

        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 2));
        productService.off(SHOP_ID, SPU_ID);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(4);
        verify(productCacheService).evict(SPU_ID);
    }

    /** RF3：平台违规下架（5）的商品，商家重新上架 → 拒绝；商家下架（4）可重新上架。 */
    @Test
    void on_rejects_platform_banned_product() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 5));
        assertThatThrownBy(() -> productService.on(SHOP_ID, SPU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("平台");
        verify(productMapper, never()).updateById(any(ProductEntity.class));

        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 4));
        productService.on(SHOP_ID, SPU_ID);
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(2);
        verify(productCacheService).evict(SPU_ID);
    }

    /** 删除：仅 0/3/4 可删；删除级联 SKU/规格/Mongo 详情并清缓存。 */
    @Test
    void delete_restricts_status_and_cascades() {
        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 2));
        assertThatThrownBy(() -> productService.delete(SHOP_ID, SPU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可删除");

        when(productMapper.selectById(SPU_ID)).thenReturn(product(SPU_ID, SHOP_ID, 4));
        productService.delete(SHOP_ID, SPU_ID);

        verify(productMapper).deleteById(SPU_ID);
        verify(productSkuMapper).delete(any(Wrapper.class));
        verify(productSpecMapper).delete(any(Wrapper.class));
        verify(mongoTemplate).remove(any(Query.class), eq(ProductDetailDoc.class));
        verify(productCacheService).evict(SPU_ID);
    }

    /** 列表：强制本店分页，视图字段映射正确。 */
    @Test
    void list_returns_page_of_views() {
        Page<ProductEntity> entityPage = new Page<>(1, 10, 1);
        entityPage.setRecords(List.of(product(SPU_ID, SHOP_ID, 1)));
        when(productMapper.selectPage(any(), any(Wrapper.class))).thenReturn(entityPage);

        Page<ProductListItemView> result = productService.list(SHOP_ID, 1, "关键词", 1, 10);

        assertThat(result.getTotal()).isEqualTo(1);
        ProductListItemView item = result.getRecords().get(0);
        assertThat(item.spuId()).isEqualTo(SPU_ID);
        assertThat(item.name()).isEqualTo("测试商品");
        assertThat(item.status()).isEqualTo(1);
    }

    /** 详情回显：规格值/SKU 组合 JSON 解析、图集拆分、Mongo 富文本带出；他店不可见。 */
    @Test
    void detail_aggregates_specs_skus_and_mongo_html() {
        ProductEntity spu = product(SPU_ID, SHOP_ID, 2);
        spu.setImgList("/static/a.png,/static/b.png");
        when(productMapper.selectById(SPU_ID)).thenReturn(spu);

        ProductSpecEntity spec = new ProductSpecEntity();
        spec.setSpecName("颜色");
        spec.setSpecValues("[\"曜石黑\",\"月牙白\"]");
        when(productSpecMapper.selectList(any(Wrapper.class))).thenReturn(List.of(spec));

        ProductSkuEntity skuEntity = new ProductSkuEntity();
        skuEntity.setId(555L);
        skuEntity.setSpecJson("{\"颜色\":\"曜石黑\"}");
        skuEntity.setPrice(new BigDecimal("199.00"));
        skuEntity.setStock(10);
        when(productSkuMapper.selectList(any(Wrapper.class))).thenReturn(List.of(skuEntity));

        ProductDetailDoc doc = new ProductDetailDoc();
        doc.setHtml("<p>图文详情</p>");
        when(mongoTemplate.findOne(any(Query.class), eq(ProductDetailDoc.class))).thenReturn(doc);

        MerchantProductDetailView view = productService.detail(SHOP_ID, SPU_ID);

        assertThat(view.imgList()).containsExactly("/static/a.png", "/static/b.png");
        assertThat(view.specs()).hasSize(1);
        assertThat(view.specs().get(0).values()).containsExactly("曜石黑", "月牙白");
        assertThat(view.skus()).hasSize(1);
        assertThat(view.skus().get(0).specValues()).containsEntry("颜色", "曜石黑");
        assertThat(view.detailHtml()).isEqualTo("<p>图文详情</p>");

        assertThatThrownBy(() -> productService.detail(8L, SPU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    private ProductSaveRequest saveRequest() {
        return new ProductSaveRequest(101L, "测试商品", "副标题", "/static/a.png",
                List.of("/static/a.png", "/static/b.png"),
                List.of(new ProductSaveRequest.SpecDef("颜色", List.of("曜石黑", "月牙白"))),
                List.of(new ProductSaveRequest.SkuDef(Map.of("颜色", "曜石黑"), new BigDecimal("199.00"), 10, null),
                        new ProductSaveRequest.SkuDef(Map.of("颜色", "月牙白"), new BigDecimal("179.00"), 5, null)),
                "<p>详情</p>");
    }

    private ProductSaveRequest.SkuDef sku(String price, int stock) {
        return new ProductSaveRequest.SkuDef(Map.of("颜色", "曜石黑"), new BigDecimal(price), stock, null);
    }

    private CategoryEntity category(Long id, int status) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setName("手机");
        entity.setStatus(status);
        return entity;
    }

    private ProductEntity product(Long id, Long shopId, int status) {
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        entity.setShopId(shopId);
        entity.setCategoryId(101L);
        entity.setName("测试商品");
        entity.setMainImg("/static/a.png");
        entity.setPrice(new BigDecimal("179.00"));
        entity.setStatus(status);
        entity.setSales(0);
        return entity;
    }
}
