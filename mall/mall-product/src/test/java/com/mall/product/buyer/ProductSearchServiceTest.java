package com.mall.product.buyer;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.product.buyer.dto.ProductDetailVO;
import com.mall.product.buyer.dto.ProductSearchRequest;
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
import com.mall.ai.gateway.AiService;
import com.mall.ai.vector.VectorStore;
import com.mall.product.config.AiSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 买家端商品搜索/详情单测（plan Task 8 Step 1）。
 *
 * <p>钉死项：RF3 非上架商品详情报业务错误、RF5 详情二次调用命中缓存（mapper 仅一次）、
 * 详情缓存 TTL 30 分钟、关键词搜索热搜分 +1、父类目命中全部启用子孙类目。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

    private static final Long SPU_ID = 999L;
    private static final Long SHOP_ID = 7L;
    private static final String CACHE_KEY = ProductCacheService.KEY_PREFIX + SPU_ID;

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private ProductSpecMapper productSpecMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ShopMapper shopMapper;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zsetOps;
    @Mock
    private AiService aiService;
    @Mock
    private VectorStore vectorStore;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiSearchProperties searchProps = new AiSearchProperties();

    private ProductSearchService searchService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForZSet()).thenReturn(zsetOps);
        lenient().when(aiService.isEmbeddingEnabled()).thenReturn(false); // 默认向量关，回归保护
        searchService = new ProductSearchService(productMapper, productSkuMapper, productSpecMapper,
                categoryMapper, shopMapper, mongoTemplate, redis, objectMapper,
                aiService, vectorStore, searchProps);
    }

    /** ① RF3：待审核/已下架/不存在等一切非上架状态的详情 → "商品不存在或已下架"。 */
    @Test
    void detail_throws_when_product_missing_or_not_on_shelf() {
        when(productMapper.selectById(SPU_ID)).thenReturn(null);
        assertThatThrownBy(() -> searchService.detail(SPU_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在或已下架");

        for (int status : new int[]{0, 1, 3, 4, 5}) {
            when(productMapper.selectById(SPU_ID)).thenReturn(onShelfProduct(status));
            assertThatThrownBy(() -> searchService.detail(SPU_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("商品不存在或已下架");
        }
    }

    /** 详情聚合：SPU + SKU（规格 JSON 解析）+ 规格定义 + Mongo 富文本 + 店铺信息。 */
    @Test
    void detail_assembles_spu_skus_specs_detail_html_and_shop() {
        stubDetailFixtures();

        ProductDetailVO vo = searchService.detail(SPU_ID);

        assertThat(vo.spu().id()).isEqualTo(SPU_ID);
        assertThat(vo.spu().name()).isEqualTo("曜石黑保温杯");
        assertThat(vo.spu().imgList()).containsExactly("/static/a.png", "/static/b.png");
        assertThat(vo.spu().price()).isEqualByComparingTo("99.00");
        assertThat(vo.skus()).hasSize(1);
        assertThat(vo.skus().get(0).specValues()).isEqualTo(Map.of("颜色", "曜石黑"));
        assertThat(vo.skus().get(0).stock()).isEqualTo(8);
        assertThat(vo.specs()).hasSize(1);
        assertThat(vo.specs().get(0).specName()).isEqualTo("颜色");
        assertThat(vo.specs().get(0).values()).containsExactly("曜石黑", "月牙白");
        assertThat(vo.detailHtml()).isEqualTo("<p>图文详情</p>");
        assertThat(vo.shop().name()).isEqualTo("山木良品");
        assertThat(vo.shop().bizStatus()).isEqualTo(1);
    }

    /** ④ RF5：首次 miss 组装并回写，二次调用直接命中缓存，mapper 全程只调一次。 */
    @Test
    void detail_second_call_hits_cache_and_mapper_runs_once() throws Exception {
        stubDetailFixtures();
        ProductDetailVO cached = detailVoFixture();
        when(valueOps.get(CACHE_KEY)).thenReturn(null, objectMapper.writeValueAsString(cached));

        ProductDetailVO first = searchService.detail(SPU_ID);
        ProductDetailVO second = searchService.detail(SPU_ID);

        assertThat(first.spu().name()).isEqualTo("曜石黑保温杯");
        assertThat(second).isEqualTo(cached);
        verify(productMapper, times(1)).selectById(anyLong());
    }

    /** 详情回写缓存：键 {@code mall:product:{spuId}}，TTL 30 分钟。 */
    @Test
    void detail_writes_cache_with_thirty_minutes_ttl() {
        stubDetailFixtures();

        searchService.detail(SPU_ID);

        verify(valueOps).set(eq(CACHE_KEY), anyString(), eq(Duration.ofMinutes(30)));
    }

    /** 缓存内容损坏时按 miss 处理，查库重建不阻断。 */
    @Test
    void detail_corrupted_cache_falls_back_to_db() {
        stubDetailFixtures();
        when(valueOps.get(CACHE_KEY)).thenReturn("{not-json");

        ProductDetailVO vo = searchService.detail(SPU_ID);

        assertThat(vo.spu().name()).isEqualTo("曜石黑保温杯");
        verify(productMapper).selectById(SPU_ID);
    }

    /** ⑤ 关键词非空（含首尾空格需归一化）搜索 → 热搜 ZSet 分数 +1；空白关键词不计分。 */
    @Test
    void search_increments_hot_score_only_when_keyword_present() {
        Page<ProductEntity> entityPage = new Page<>(1, 10, 1);
        entityPage.setRecords(List.of(onShelfProduct(2)));
        when(productMapper.selectPage(any(), any())).thenReturn(entityPage);

        Page<ProductListItemView> result = searchService.search(
                new ProductSearchRequest(" 手机 ", null, null, "default", 1, 10));

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().get(0).name()).isEqualTo("曜石黑保温杯");
        verify(zsetOps).incrementScore(ProductSearchService.HOT_SEARCH_KEY, "手机", 1.0);

        searchService.search(new ProductSearchRequest("   ", null, null, "default", 1, 10));
        verify(zsetOps, times(1)).incrementScore(anyString(), anyString(), anyDouble());
    }

    /** 非法排序参数 → 400 参数错误。 */
    @Test
    void search_rejects_unknown_sort() {
        assertThatThrownBy(() -> searchService.search(
                new ProductSearchRequest(null, null, null, "weird", 1, 10)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("排序")
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(400);
    }

    /** ③ 类目收集：目标类目 + 全部启用子孙类目；停用子树不纳入。 */
    @Test
    void collect_enabled_category_ids_includes_enabled_descendants_only() {
        // 启用类目（selectList 带 status=1 过滤，mock 即库中启用集合）：101 → 102/103
        when(categoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                category(101L, 0L), category(102L, 101L), category(103L, 101L)));

        assertThat(searchService.collectEnabledCategoryIds(101L))
                .containsExactlyInAnyOrder(101L, 102L, 103L);
        assertThat(searchService.collectEnabledCategoryIds(102L))
                .containsExactly(102L);
    }

    /** 热搜榜：ZREVRANGE Top10 保序返回；空榜返回空列表。 */
    @Test
    void hot_search_returns_top10_in_score_order() {
        when(zsetOps.reverseRange(ProductSearchService.HOT_SEARCH_KEY, 0, 9))
                .thenReturn(new LinkedHashSet<>(List.of("手机", "耳机")));

        assertThat(searchService.hotSearch()).containsExactly("手机", "耳机");

        when(zsetOps.reverseRange(ProductSearchService.HOT_SEARCH_KEY, 0, 9)).thenReturn(null);
        assertThat(searchService.hotSearch()).isEmpty();
    }

    private void stubDetailFixtures() {
        when(productMapper.selectById(SPU_ID)).thenReturn(onShelfProduct(2));

        ProductSpecEntity spec = new ProductSpecEntity();
        spec.setSpuId(SPU_ID);
        spec.setSpecName("颜色");
        spec.setSpecValues("[\"曜石黑\",\"月牙白\"]");
        when(productSpecMapper.selectList(any(Wrapper.class))).thenReturn(List.of(spec));

        ProductSkuEntity sku = new ProductSkuEntity();
        sku.setId(11L);
        sku.setSpuId(SPU_ID);
        sku.setSpecJson("{\"颜色\":\"曜石黑\"}");
        sku.setPrice(new BigDecimal("99.00"));
        sku.setStock(8);
        sku.setSkuImg("/static/sku.png");
        when(productSkuMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sku));

        ProductDetailDoc doc = new ProductDetailDoc();
        doc.setSpuId(SPU_ID);
        doc.setHtml("<p>图文详情</p>");
        when(mongoTemplate.findOne(any(Query.class), eq(ProductDetailDoc.class))).thenReturn(doc);

        ShopEntity shop = new ShopEntity();
        shop.setId(SHOP_ID);
        shop.setName("山木良品");
        shop.setLogo("/static/logo.png");
        shop.setIntro("温暖木质好物");
        shop.setBizStatus(1);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop);
    }

    private ProductDetailVO detailVoFixture() {
        return new ProductDetailVO(
                new ProductDetailVO.SpuView(SPU_ID, SHOP_ID, 102L, "缓存商品", "副标题",
                        "/static/a.png", List.of("/static/a.png"), new BigDecimal("66.00"),
                        3, new BigDecimal("4.9"), 2),
                List.of(new ProductDetailVO.SkuView(11L, Map.of("颜色", "曜石黑"),
                        new BigDecimal("66.00"), 8, "/static/sku.png")),
                List.of(new ProductDetailVO.SpecView("颜色", List.of("曜石黑", "月牙白"))),
                "<p>缓存详情</p>",
                new ProductDetailVO.ShopView(SHOP_ID, "山木良品", "/static/logo.png", "温暖木质好物", 1));
    }

    private ProductEntity onShelfProduct(int status) {
        ProductEntity entity = new ProductEntity();
        entity.setId(SPU_ID);
        entity.setShopId(SHOP_ID);
        entity.setCategoryId(102L);
        entity.setName("曜石黑保温杯");
        entity.setSubTitle("温暖木质");
        entity.setMainImg("/static/a.png");
        entity.setImgList("/static/a.png,/static/b.png");
        entity.setPrice(new BigDecimal("99.00"));
        entity.setSales(3);
        entity.setAvgRating(new BigDecimal("4.9"));
        entity.setCommentCount(2);
        entity.setStatus(status);
        return entity;
    }

    private CategoryEntity category(Long id, Long parentId) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setParentId(parentId);
        entity.setStatus(1);
        return entity;
    }
}
