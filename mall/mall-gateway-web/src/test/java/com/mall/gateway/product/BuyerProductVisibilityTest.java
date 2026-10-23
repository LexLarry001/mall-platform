package com.mall.gateway.product;

import com.jayway.jsonpath.JsonPath;
import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.product.buyer.ProductSearchService;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ProductSkuEntity;
import com.mall.product.entity.ProductSpecEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ProductSkuMapper;
import com.mall.product.mapper.ProductSpecMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.ProductDetailDoc;
import com.mall.security.captcha.CaptchaService;
import com.mall.security.token.TokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 买家端商品可见性与缓存链路测试（plan Task 8 Step 1/4）。
 *
 * <p>真实安全过滤链 + 真实 MySQL + 真实 MongoDB + 真实 Redis（全部匿名访问，无 token）。
 * 测试数据以 {@code BUYERFLOW_} 前缀命名，SPU/SKU/规格/Mongo 详情/缓存键/热搜词前后物理清理。</p>
 */
@SpringBootTest(
        classes = BuyerProductVisibilityTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + BuyerProductVisibilityTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class BuyerProductVisibilityTest {

    static final String SECRET = "buyer-product-flow-test-secret-0123456789abcd";

    @SpringBootApplication(scanBasePackages = {
            "com.mall.merchant", "com.mall.admin", "com.mall.product",
            "com.mall.gateway.auth", "com.mall.gateway.config", "com.mall.ai", "com.mall.security"})
    @MapperScan({"com.mall.merchant.mapper", "com.mall.product.mapper",
            "com.mall.admin.mapper", "com.mall.user.mapper"})
    @Import(GlobalExceptionHandler.class)
    @EnableMongoRepositories(basePackages = "com.mall")
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private ProductSpecMapper productSpecMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    private final List<Long> createdSpuIds = new ArrayList<>();
    private long merchantIdSeq = 891_000_000_000L;

    @BeforeEach
    void setUp() {
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /** ① RF3：仅 status=2 详情可见；0/1/3/4/5 与不存在均"商品不存在或已下架"。 */
    @Test
    void detail_visible_only_when_on_shelf() throws Exception {
        Long shopId = insertShop("BUYERFLOW_店铺");
        Long[] byStatus = new Long[6];
        for (int status = 0; status <= 5; status++) {
            byStatus[status] = insertProduct(shopId, "BUYERFLOW_状态" + status, 102L, status, "10.00");
        }

        mockMvc.perform(get("/api/product/{spuId}", byStatus[2]))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spu.name").value("BUYERFLOW_状态2"));

        for (int status : new int[]{0, 1, 3, 4, 5}) {
            mockMvc.perform(get("/api/product/{spuId}", byStatus[status]))
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.message").value(containsString("商品不存在或已下架")));
        }
        mockMvc.perform(get("/api/product/{spuId}", 999_999_999_999L))
                .andExpect(jsonPath("$.code").value(1000));
    }

    /** ② 搜索仅返回 status=2 商品。 */
    @Test
    void search_returns_only_on_shelf_products() throws Exception {
        Long shopId = insertShop("BUYERFLOW_店铺");
        insertProduct(shopId, "BUYERFLOW_筛选_在售甲", 102L, 2, "10.00");
        insertProduct(shopId, "BUYERFLOW_筛选_在售乙", 102L, 2, "20.00");
        insertProduct(shopId, "BUYERFLOW_筛选_待审", 102L, 1, "30.00");
        insertProduct(shopId, "BUYERFLOW_筛选_已下架", 102L, 4, "40.00");

        mockMvc.perform(get("/api/product/search").param("keyword", "BUYERFLOW_筛选"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[*].name", everyItem(containsString("在售"))));
    }

    /** ③ categoryId=父类目 → 命中全部启用子孙类目商品；无关类目不命中。 */
    @Test
    void search_by_parent_category_includes_children() throws Exception {
        Long shopId = insertShop("BUYERFLOW_店铺");
        // 102 手机 是 101 数码家电 的子类目
        insertProduct(shopId, "BUYERFLOW_类目_手机壳", 102L, 2, "9.90");

        mockMvc.perform(get("/api/product/search")
                        .param("keyword", "BUYERFLOW_类目")
                        .param("categoryId", "101"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("BUYERFLOW_类目_手机壳"));

        mockMvc.perform(get("/api/product/search")
                        .param("keyword", "BUYERFLOW_类目")
                        .param("categoryId", "105"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /** ④ RF5：详情首次读取回写缓存（TTL≈1800s），改库后二次读取仍返回缓存旧值。 */
    @Test
    void detail_cache_written_with_ttl_and_serves_second_read() throws Exception {
        Long shopId = insertShop("BUYERFLOW_店铺");
        Long spuId = insertProduct(shopId, "BUYERFLOW_缓存商品", 102L, 2, "66.00");
        insertSku(spuId, "66.00", 8);
        insertSpec(spuId);
        saveDetailDoc(spuId, "<p>BUYERFLOW详情</p>");

        mockMvc.perform(get("/api/product/{spuId}", spuId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spu.name").value("BUYERFLOW_缓存商品"))
                .andExpect(jsonPath("$.data.skus[0].stock").value(8))
                .andExpect(jsonPath("$.data.specs[0].specName").value("颜色"))
                .andExpect(jsonPath("$.data.detailHtml").value("<p>BUYERFLOW详情</p>"))
                .andExpect(jsonPath("$.data.shop.name").value("BUYERFLOW_店铺"));

        String cacheKey = "mall:product:" + spuId;
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue();
        Long ttl = stringRedisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(60L, 1800L);

        // 改库后再次读取仍返回缓存旧名，证明二次读取命中缓存而非查库
        jdbcTemplate.update("UPDATE product SET name = 'BUYERFLOW_缓存改名' WHERE id = ?", spuId);
        mockMvc.perform(get("/api/product/{spuId}", spuId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spu.name").value("BUYERFLOW_缓存商品"));
    }

    /** ⑤ 关键词搜索累计热搜分；热搜榜按分数降序返回。 */
    @Test
    void hot_search_accumulates_keyword_scores() throws Exception {
        Long shopId = insertShop("BUYERFLOW_店铺");
        insertProduct(shopId, "BUYERFLOW_占位", 102L, 2, "1.00");

        searchByKeyword("BUYERFLOW热词甲");
        searchByKeyword("BUYERFLOW热词甲");
        searchByKeyword("BUYERFLOW热词乙");

        Double scoreA = stringRedisTemplate.opsForZSet()
                .score(ProductSearchService.HOT_SEARCH_KEY, "BUYERFLOW热词甲");
        Double scoreB = stringRedisTemplate.opsForZSet()
                .score(ProductSearchService.HOT_SEARCH_KEY, "BUYERFLOW热词乙");
        assertThat(scoreA).isEqualTo(2.0);
        assertThat(scoreB).isEqualTo(1.0);

        MvcResult result = mockMvc.perform(get("/api/product/hot-search"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        List<String> words = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data");
        assertThat(words).contains("BUYERFLOW热词甲", "BUYERFLOW热词乙");
        assertThat(words.indexOf("BUYERFLOW热词甲")).isLessThan(words.indexOf("BUYERFLOW热词乙"));
    }

    /** 店铺过滤与排序：shopId 精确过滤；价格升/降序；非法排序 400。 */
    @Test
    void search_filters_by_shop_and_sorts_by_price() throws Exception {
        Long shopA = insertShop("BUYERFLOW_店铺甲");
        Long shopB = insertShop("BUYERFLOW_店铺乙");
        insertProduct(shopA, "BUYERFLOW_店铺排序_甲", 102L, 2, "10.00");
        insertProduct(shopB, "BUYERFLOW_店铺排序_乙", 102L, 2, "20.00");

        mockMvc.perform(get("/api/product/search")
                        .param("keyword", "BUYERFLOW_店铺排序")
                        .param("shopId", shopA.toString()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("BUYERFLOW_店铺排序_甲"));

        mockMvc.perform(get("/api/product/search")
                        .param("keyword", "BUYERFLOW_店铺排序")
                        .param("sort", "price_asc"))
                .andExpect(jsonPath("$.data.records[0].name").value("BUYERFLOW_店铺排序_甲"))
                .andExpect(jsonPath("$.data.records[1].name").value("BUYERFLOW_店铺排序_乙"));

        mockMvc.perform(get("/api/product/search")
                        .param("keyword", "BUYERFLOW_店铺排序")
                        .param("sort", "price_desc"))
                .andExpect(jsonPath("$.data.records[0].name").value("BUYERFLOW_店铺排序_乙"));

        mockMvc.perform(get("/api/product/search").param("sort", "weird"))
                .andExpect(jsonPath("$.code").value(400));
    }

    private void searchByKeyword(String keyword) throws Exception {
        mockMvc.perform(get("/api/product/search").param("keyword", keyword))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Long insertShop(String name) {
        ShopEntity shop = new ShopEntity();
        shop.setMerchantId(merchantIdSeq++);
        shop.setName(name);
        shop.setBizStatus(1);
        shopMapper.insert(shop);
        return shop.getId();
    }

    private Long insertProduct(Long shopId, String name, Long categoryId, int status, String price) {
        ProductEntity spu = new ProductEntity();
        spu.setShopId(shopId);
        spu.setCategoryId(categoryId);
        spu.setName(name);
        spu.setMainImg("/static/202610/a.png");
        spu.setPrice(new BigDecimal(price));
        spu.setSales(0);
        spu.setStatus(status);
        productMapper.insert(spu);
        createdSpuIds.add(spu.getId());
        return spu.getId();
    }

    private void insertSku(Long spuId, String price, int stock) {
        ProductSkuEntity sku = new ProductSkuEntity();
        sku.setSpuId(spuId);
        sku.setSpecJson("{\"颜色\":\"曜石黑\"}");
        sku.setPrice(new BigDecimal(price));
        sku.setStock(stock);
        productSkuMapper.insert(sku);
    }

    private void insertSpec(Long spuId) {
        ProductSpecEntity spec = new ProductSpecEntity();
        spec.setSpuId(spuId);
        spec.setSpecName("颜色");
        spec.setSpecValues("[\"曜石黑\",\"月牙白\"]");
        productSpecMapper.insert(spec);
    }

    private void saveDetailDoc(Long spuId, String html) {
        ProductDetailDoc doc = new ProductDetailDoc();
        doc.setSpuId(spuId);
        doc.setHtml(html);
        doc.setGallery(List.of());
        doc.setUpdateTime(LocalDateTime.now());
        mongoTemplate.save(doc);
    }

    private void cleanFixtures() {
        if (!createdSpuIds.isEmpty()) {
            mongoTemplate.remove(Query.query(Criteria.where("spuId").in(createdSpuIds)),
                    ProductDetailDoc.class);
            createdSpuIds.forEach(id -> stringRedisTemplate.delete("mall:product:" + id));
            String ids = createdSpuIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM product_sku WHERE spu_id IN (" + ids + ")");
            jdbcTemplate.update("DELETE FROM product_spec WHERE spu_id IN (" + ids + ")");
            createdSpuIds.clear();
        }
        jdbcTemplate.update("DELETE FROM product WHERE name LIKE 'BUYERFLOW\\_%'");
        jdbcTemplate.update("DELETE FROM shop WHERE name LIKE 'BUYERFLOW\\_%'");
        stringRedisTemplate.opsForZSet().remove(ProductSearchService.HOT_SEARCH_KEY,
                "BUYERFLOW_筛选", "BUYERFLOW_类目", "BUYERFLOW_店铺排序", "BUYERFLOW热词甲", "BUYERFLOW热词乙");
    }
}
