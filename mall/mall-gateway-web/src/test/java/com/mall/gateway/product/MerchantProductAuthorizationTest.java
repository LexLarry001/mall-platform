package com.mall.gateway.product;

import com.jayway.jsonpath.JsonPath;
import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.ProductDetailDoc;
import com.mall.security.captcha.CaptchaService;
import com.mall.security.jwt.JwtTokenProvider;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家商品发布/编辑/上下架权限与状态机链路测试（plan Task 6 Step 1/4）。
 *
 * <p>真实安全过滤链 + 真实 MySQL + 真实 MongoDB + 真实 Redis；TokenStore / CaptchaService mock。
 * 测试数据以 {@code MERCFLOW_} 前缀命名，SPU/店铺/类目/Mongo 文档/缓存键前后物理清理。</p>
 */
@SpringBootTest(
        classes = MerchantProductAuthorizationTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + MerchantProductAuthorizationTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class MerchantProductAuthorizationTest {

    static final String SECRET = "merchant-product-flow-test-secret-0123456789ab";

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
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
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
    private long merchantIdSeq = 880_000_000_000L;

    @BeforeEach
    void setUp() {
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /** RF2：入驻审核未通过（shopId 为空）的商家调用商品接口 → "入驻审核未通过，暂不能操作"。 */
    @Test
    void unapproved_merchant_is_rejected() throws Exception {
        Long categoryId = insertCategory("MERCFLOW_类目");
        String token = merchantToken(901L, null);

        mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, "MERCFLOW_未过审商品")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("入驻审核未通过，暂不能操作"));

        mockMvc.perform(get("/api/merchant/product/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("入驻审核未通过，暂不能操作"));
    }

    /** 发布 → 待审核 → 编辑回待审核（缓存失效）→ 下架 → 上架 → 平台下架禁上架 → 删除 全状态机链路。 */
    @Test
    void full_status_machine_flow() throws Exception {
        Long shopId = insertShop("MERCFLOW_店铺A");
        Long categoryId = insertCategory("MERCFLOW_类目");
        String merchant = bearer(merchantToken(902L, shopId));

        // 发布 → status=1 待审核，price=SKU 最低价，Mongo 详情写入
        MvcResult created = mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, "MERCFLOW_商品A1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Long spuId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.data")).longValue();
        createdSpuIds.add(spuId);

        ProductEntity spu = productMapper.selectById(spuId);
        assertThat(spu.getStatus()).isEqualTo(1);
        assertThat(spu.getPrice()).isEqualByComparingTo("179.00");
        assertThat(spu.getShopId()).isEqualTo(shopId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_sku WHERE spu_id = ?", Long.class, spuId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_spec WHERE spu_id = ?", Long.class, spuId)).isEqualTo(1);
        ProductDetailDoc doc = mongoTemplate.findOne(
                Query.query(Criteria.where("spuId").is(spuId)), ProductDetailDoc.class);
        assertThat(doc).isNotNull();
        assertThat(doc.getHtml()).isEqualTo("<p>详情</p>");

        // 模拟平台审核通过 → status=2，并预置详情缓存键
        jdbcTemplate.update("UPDATE product SET status = 2 WHERE id = ?", spuId);
        String cacheKey = "mall:product:" + spuId;
        stringRedisTemplate.opsForValue().set(cacheKey, "cached");

        // 编辑已上架商品 → status 回 1，缓存键被删（RF3/RF5）
        mockMvc.perform(put("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, "MERCFLOW_商品A1改名")))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(1);
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isFalse();

        // 详情回显：名称/SKU/Mongo html 均为新值
        mockMvc.perform(get("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("MERCFLOW_商品A1改名"))
                .andExpect(jsonPath("$.data.skus", hasSize(2)))
                .andExpect(jsonPath("$.data.detailHtml").value("<p>详情</p>"));

        // 模拟再次审核通过 → 商家下架 → status=4
        jdbcTemplate.update("UPDATE product SET status = 2 WHERE id = ?", spuId);
        mockMvc.perform(post("/api/merchant/product/{spuId}/off", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(4);

        // 重新上架 → status=2
        mockMvc.perform(post("/api/merchant/product/{spuId}/on", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(2);

        // 平台违规下架（5）→ 商家上架被拒绝（RF3）
        jdbcTemplate.update("UPDATE product SET status = 5 WHERE id = ?", spuId);
        mockMvc.perform(post("/api/merchant/product/{spuId}/on", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("平台")));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(5);

        // 已上架（2）不可删除；商家下架（4）后可删除：逻辑删除 + Mongo 详情清除
        jdbcTemplate.update("UPDATE product SET status = 2 WHERE id = ?", spuId);
        mockMvc.perform(delete("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant))
                .andExpect(jsonPath("$.code").value(1000));
        jdbcTemplate.update("UPDATE product SET status = 4 WHERE id = ?", spuId);
        mockMvc.perform(delete("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchant))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(productMapper.selectById(spuId)).isNull();
        assertThat(mongoTemplate.findOne(
                Query.query(Criteria.where("spuId").is(spuId)), ProductDetailDoc.class)).isNull();
    }

    /** RF1：商家 B 改/删/下架商家 A 的商品 → 业务错误且数据不变。 */
    @Test
    void cross_shop_operations_are_rejected() throws Exception {
        Long shopA = insertShop("MERCFLOW_店铺A");
        Long shopB = insertShop("MERCFLOW_店铺B");
        Long categoryId = insertCategory("MERCFLOW_类目");
        String merchantA = bearer(merchantToken(903L, shopA));
        String merchantB = bearer(merchantToken(904L, shopB));

        MvcResult created = mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, merchantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, "MERCFLOW_商品A2")))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Long spuId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.data")).longValue();
        createdSpuIds.add(spuId);

        mockMvc.perform(put("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchantB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, "MERCFLOW_篡改")))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/merchant/product/{spuId}/off", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchantB))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(delete("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchantB))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(get("/api/merchant/product/{spuId}", spuId)
                        .header(HttpHeaders.AUTHORIZATION, merchantB))
                .andExpect(jsonPath("$.code").value(1000));

        ProductEntity unchanged = productMapper.selectById(spuId);
        assertThat(unchanged.getName()).isEqualTo("MERCFLOW_商品A2");
        assertThat(unchanged.getStatus()).isEqualTo(1);
    }

    /** 发布校验：空 SKU / 重复规格组合 / 负库存 → 400 或业务错误，不落库。 */
    @Test
    void create_validation_is_enforced() throws Exception {
        Long shopId = insertShop("MERCFLOW_店铺A");
        Long categoryId = insertCategory("MERCFLOW_类目");
        String merchant = bearer(merchantToken(905L, shopId));

        // 空 SKU → 400
        mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"name":"MERCFLOW_空SKU","mainImg":"/static/a.png","skus":[]}
                                """.formatted(categoryId)))
                .andExpect(jsonPath("$.code").value(400));

        // 负库存 → 400
        mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"name":"MERCFLOW_负库存","mainImg":"/static/a.png",
                                 "skus":[{"specValues":{"颜色":"黑"},"price":9.90,"stock":-1}]}
                                """.formatted(categoryId)))
                .andExpect(jsonPath("$.code").value(400));

        // 重复规格组合 → 业务错误
        mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"name":"MERCFLOW_重复规格","mainImg":"/static/a.png",
                                 "skus":[{"specValues":{"颜色":"黑"},"price":9.90,"stock":1},
                                         {"specValues":{"颜色":"黑"},"price":8.90,"stock":2}]}
                                """.formatted(categoryId)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("重复")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE name LIKE 'MERCFLOW\\_%'", Long.class)).isZero();
    }

    /** 列表：强制本店隔离 + 状态/关键词过滤。 */
    @Test
    void list_is_isolated_by_shop_and_filterable() throws Exception {
        Long shopA = insertShop("MERCFLOW_店铺A");
        Long shopB = insertShop("MERCFLOW_店铺B");
        Long categoryId = insertCategory("MERCFLOW_类目");
        String merchantA = bearer(merchantToken(906L, shopA));
        String merchantB = bearer(merchantToken(907L, shopB));

        createdSpuIds.add(createViaApi(merchantA, categoryId, "MERCFLOW_手机壳"));
        createdSpuIds.add(createViaApi(merchantA, categoryId, "MERCFLOW_数据线"));
        createdSpuIds.add(createViaApi(merchantB, categoryId, "MERCFLOW_别店商品"));

        mockMvc.perform(get("/api/merchant/product/list")
                        .header(HttpHeaders.AUTHORIZATION, merchantA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[*].name").value(hasItem("MERCFLOW_手机壳")))
                .andExpect(jsonPath("$.data.records[*].name").value(not(hasItem("MERCFLOW_别店商品"))));

        mockMvc.perform(get("/api/merchant/product/list")
                        .param("keyword", "手机")
                        .header(HttpHeaders.AUTHORIZATION, merchantA))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[*].name").value(hasItem("MERCFLOW_手机壳")));

        // A 的商品均为待审核（1），按 status=2 过滤为空
        mockMvc.perform(get("/api/merchant/product/list")
                        .param("status", "2")
                        .header(HttpHeaders.AUTHORIZATION, merchantA))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /** 鉴权：匿名 401；买家 token 403。 */
    @Test
    void anonymous_and_buyer_are_blocked() throws Exception {
        mockMvc.perform(get("/api/merchant/product/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String buyerToken = jwtTokenProvider.createToken(5L, AccountType.USER, null);
        when(tokenStore.get(buyerToken)).thenReturn(new LoginUser(5L, AccountType.USER, null, Set.of()));
        when(tokenStore.isCurrent(buyerToken, AccountType.USER, 5L)).thenReturn(true);

        mockMvc.perform(get("/api/merchant/product/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private Long createViaApi(String merchantBearer, Long categoryId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/merchant/product")
                        .header(HttpHeaders.AUTHORIZATION, merchantBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, name)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String createBody(Long categoryId, String name) {
        return """
                {
                  "categoryId": %d,
                  "name": "%s",
                  "subTitle": "副标题",
                  "mainImg": "/static/202610/a.png",
                  "imgList": ["/static/202610/a.png", "/static/202610/b.png"],
                  "specs": [{"specName": "颜色", "values": ["曜石黑", "月牙白"]}],
                  "skus": [
                    {"specValues": {"颜色": "曜石黑"}, "price": 199.00, "stock": 10, "skuImg": "/static/202610/a.png"},
                    {"specValues": {"颜色": "月牙白"}, "price": 179.00, "stock": 5}
                  ],
                  "detailHtml": "<p>详情</p>"
                }
                """.formatted(categoryId, name);
    }

    private String merchantToken(Long accountId, Long shopId) {
        String token = jwtTokenProvider.createToken(accountId, AccountType.MERCHANT, shopId);
        when(tokenStore.get(token)).thenReturn(new LoginUser(accountId, AccountType.MERCHANT, shopId, Set.of()));
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, accountId)).thenReturn(true);
        return token;
    }

    private Long insertShop(String name) {
        ShopEntity shop = new ShopEntity();
        shop.setMerchantId(merchantIdSeq++);
        shop.setName(name);
        shop.setBizStatus(1);
        shopMapper.insert(shop);
        return shop.getId();
    }

    private Long insertCategory(String name) {
        CategoryEntity entity = new CategoryEntity();
        entity.setParentId(0L);
        entity.setName(name);
        entity.setSort(1);
        entity.setStatus(1);
        categoryMapper.insert(entity);
        return entity.getId();
    }

    private void cleanFixtures() {
        if (!createdSpuIds.isEmpty()) {
            String ids = createdSpuIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
            jdbcTemplate.update("DELETE FROM product_sku WHERE spu_id IN (" + ids + ")");
            jdbcTemplate.update("DELETE FROM product_spec WHERE spu_id IN (" + ids + ")");
            mongoTemplate.remove(Query.query(Criteria.where("spuId").in(createdSpuIds)), ProductDetailDoc.class);
            createdSpuIds.forEach(id -> stringRedisTemplate.delete("mall:product:" + id));
            createdSpuIds.clear();
        }
        jdbcTemplate.update("DELETE FROM product WHERE name LIKE 'MERCFLOW\\_%'");
        jdbcTemplate.update("DELETE FROM shop WHERE name LIKE 'MERCFLOW\\_%'");
        jdbcTemplate.update("DELETE FROM category WHERE name LIKE 'MERCFLOW\\_%'");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
