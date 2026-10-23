package com.mall.gateway.product;

import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.MerchantAuditLogDoc;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台商品审核与违规下架管控链路测试（plan Task 7 Step 1/4）。
 *
 * <p>真实安全过滤链 + 真实 MySQL + 真实 MongoDB + 真实 Redis；TokenStore / CaptchaService mock。
 * 测试数据以 {@code ADMINFLOW_} 前缀命名，SPU/店铺/Mongo 留痕/缓存键前后物理清理。</p>
 */
@SpringBootTest(
        classes = AdminProductAuthTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + AdminProductAuthTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class AdminProductAuthTest {

    static final String SECRET = "admin-product-flow-test-secret-0123456789abcd";

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
    private long merchantIdSeq = 890_000_000_000L;

    @BeforeEach
    void setUp() {
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /** 审核全链路：待审列表（含店铺名）→ 通过（status=2 + 缓存删除 + PASS 留痕）→ 重复通过被拒 → 驳回（status=3 + 原因 + REJECT 留痕）。 */
    @Test
    void audit_flow_pass_then_reject() throws Exception {
        Long shopId = insertShop("ADMINFLOW_店铺");
        Long spuId = insertProduct(shopId, "ADMINFLOW_待审商品", 1);
        String admin = bearer(adminToken(Set.of("product:audit:list", "product:audit:handle")));

        // 待审核列表：仅 status=1，店铺名联查
        mockMvc.perform(get("/api/admin/product/audit/list")
                        .param("keyword", "ADMINFLOW")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("ADMINFLOW_待审商品"))
                .andExpect(jsonPath("$.data.records[0].shopName").value("ADMINFLOW_店铺"));

        // 预置详情缓存键：审核通过后必须被删除（RF5）
        String cacheKey = "mall:product:" + spuId;
        stringRedisTemplate.opsForValue().set(cacheKey, "cached");

        // 通过 → status=2，缓存删除，Mongo 写 PASS 留痕
        mockMvc.perform(post("/api/admin/product/{spuId}/audit/pass", spuId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(2);
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isFalse();
        assertThat(actionsOf(spuId)).contains(MerchantAuditLogDoc.ACTION_PASS);

        // 重复审核非待审商品 → 业务错误（幂等）
        mockMvc.perform(post("/api/admin/product/{spuId}/audit/pass", spuId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("待审核")));

        // 重置回待审核 → 驳回不填原因 → 400；填原因 → status=3 + auditRemark + REJECT 留痕
        jdbcTemplate.update("UPDATE product SET status = 1 WHERE id = ?", spuId);
        mockMvc.perform(post("/api/admin/product/{spuId}/audit/reject", spuId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/admin/product/{spuId}/audit/reject", spuId)
                        .param("remark", "资质图片模糊")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0));
        ProductEntity rejected = productMapper.selectById(spuId);
        assertThat(rejected.getStatus()).isEqualTo(3);
        assertThat(rejected.getAuditRemark()).isEqualTo("资质图片模糊");
        assertThat(actionsOf(spuId)).contains(MerchantAuditLogDoc.ACTION_REJECT);
    }

    /** 违规下架管控：2→5（缓存删除 + 留痕）；4→5 允许；1/5 拒绝；原因必填。 */
    @Test
    void violation_off_flow() throws Exception {
        Long shopId = insertShop("ADMINFLOW_店铺");
        Long spuId = insertProduct(shopId, "ADMINFLOW_在售商品", 2);
        String admin = bearer(adminToken(Set.of("product:manage:off")));

        // 原因必填
        mockMvc.perform(post("/api/admin/product/{spuId}/violation-off", spuId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(400));

        String cacheKey = "mall:product:" + spuId;
        stringRedisTemplate.opsForValue().set(cacheKey, "cached");

        // 已上架（2）→ 违规下架（5）：缓存删除 + auditRemark + VIOLATION_OFF 留痕
        mockMvc.perform(post("/api/admin/product/{spuId}/violation-off", spuId)
                        .param("reason", "抽检不合格")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0));
        ProductEntity banned = productMapper.selectById(spuId);
        assertThat(banned.getStatus()).isEqualTo(5);
        assertThat(banned.getAuditRemark()).isEqualTo("抽检不合格");
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isFalse();
        assertThat(actionsOf(spuId)).contains(MerchantAuditLogDoc.ACTION_VIOLATION_OFF);

        // 已是 5 → 重复下架拒绝
        mockMvc.perform(post("/api/admin/product/{spuId}/violation-off", spuId)
                        .param("reason", "再次")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(1000));

        // 商家下架（4）→ 允许转 5（plan Step 1 ④）
        jdbcTemplate.update("UPDATE product SET status = 4 WHERE id = ?", spuId);
        mockMvc.perform(post("/api/admin/product/{spuId}/violation-off", spuId)
                        .param("reason", "历史违规追罚")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(5);

        // 待审核（1）→ 拒绝（只能管控已上架/商家下架的商品）
        jdbcTemplate.update("UPDATE product SET status = 1 WHERE id = ?", spuId);
        mockMvc.perform(post("/api/admin/product/{spuId}/violation-off", spuId)
                        .param("reason", "越态")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(1000));
        assertThat(productMapper.selectById(spuId).getStatus()).isEqualTo(1);
    }

    /** 商品管控列表：全平台视角，status/keyword 过滤 + 店铺名联查。 */
    @Test
    void manage_list_filters_and_joins_shop_name() throws Exception {
        Long shopA = insertShop("ADMINFLOW_店铺甲");
        Long shopB = insertShop("ADMINFLOW_店铺乙");
        insertProduct(shopA, "ADMINFLOW_保温杯", 2);
        insertProduct(shopB, "ADMINFLOW_雨伞", 4);
        String admin = bearer(adminToken(Set.of("product:manage:list")));

        mockMvc.perform(get("/api/admin/product/list")
                        .param("keyword", "ADMINFLOW")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[*].shopName")
                        .value(hasItem("ADMINFLOW_店铺甲")))
                .andExpect(jsonPath("$.data.records[*].shopName")
                        .value(hasItem("ADMINFLOW_店铺乙")));

        mockMvc.perform(get("/api/admin/product/list")
                        .param("keyword", "ADMINFLOW")
                        .param("status", "2")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("ADMINFLOW_保温杯"));

        mockMvc.perform(get("/api/admin/product/list")
                        .param("keyword", "ADMINFLOW")
                        .param("shopId", shopB.toString())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("ADMINFLOW_雨伞"));
    }

    /** 鉴权：无权限码 → 403（plan Step 1 ③）；匿名 → 401；商家 token → 403。 */
    @Test
    void permission_is_enforced() throws Exception {
        Long shopId = insertShop("ADMINFLOW_店铺");
        Long spuId = insertProduct(shopId, "ADMINFLOW_权限商品", 1);

        // 具备 manage 权限但缺 audit 权限：审核列表/通过均 403
        String noAuditPerm = bearer(adminToken(Set.of("product:manage:list")));
        mockMvc.perform(get("/api/admin/product/audit/list")
                        .header(HttpHeaders.AUTHORIZATION, noAuditPerm))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/admin/product/{spuId}/audit/pass", spuId)
                        .header(HttpHeaders.AUTHORIZATION, noAuditPerm))
                .andExpect(status().isForbidden());

        // 具备 audit 权限但缺 manage 权限：管控列表/违规下架均 403
        String noManagePerm = bearer(adminToken(Set.of("product:audit:handle")));
        mockMvc.perform(get("/api/admin/product/list")
                        .header(HttpHeaders.AUTHORIZATION, noManagePerm))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/product/{spuId}/violation-off", spuId)
                        .param("reason", "无权限")
                        .header(HttpHeaders.AUTHORIZATION, noManagePerm))
                .andExpect(status().isForbidden());

        // 匿名 → 401
        mockMvc.perform(get("/api/admin/product/audit/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // 商家 token → 403（跨端越权）
        String merchant = jwtTokenProvider.createToken(9L, AccountType.MERCHANT, shopId);
        when(tokenStore.get(merchant)).thenReturn(new LoginUser(9L, AccountType.MERCHANT, shopId, Set.of()));
        when(tokenStore.isCurrent(merchant, AccountType.MERCHANT, 9L)).thenReturn(true);
        mockMvc.perform(get("/api/admin/product/audit/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private List<String> actionsOf(Long spuId) {
        return mongoTemplate.find(Query.query(Criteria.where("targetType").is("PRODUCT")
                                .and("targetId").is(spuId)), MerchantAuditLogDoc.class)
                .stream().map(MerchantAuditLogDoc::getAction).toList();
    }

    private String adminToken(Set<String> permissions) {
        String token = jwtTokenProvider.createToken(1L, AccountType.ADMIN, null);
        when(tokenStore.get(token)).thenReturn(new LoginUser(1L, AccountType.ADMIN, null, permissions));
        when(tokenStore.isCurrent(token, AccountType.ADMIN, 1L)).thenReturn(true);
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

    private Long insertProduct(Long shopId, String name, int status) {
        ProductEntity spu = new ProductEntity();
        spu.setShopId(shopId);
        spu.setCategoryId(101L);
        spu.setName(name);
        spu.setMainImg("/static/202610/a.png");
        spu.setPrice(new BigDecimal("99.00"));
        spu.setSales(0);
        spu.setStatus(status);
        productMapper.insert(spu);
        createdSpuIds.add(spu.getId());
        return spu.getId();
    }

    private void cleanFixtures() {
        if (!createdSpuIds.isEmpty()) {
            mongoTemplate.remove(Query.query(Criteria.where("targetType").is("PRODUCT")
                    .and("targetId").in(createdSpuIds)), MerchantAuditLogDoc.class);
            createdSpuIds.forEach(id -> stringRedisTemplate.delete("mall:product:" + id));
            createdSpuIds.clear();
        }
        jdbcTemplate.update("DELETE FROM product WHERE name LIKE 'ADMINFLOW\\_%'");
        jdbcTemplate.update("DELETE FROM shop WHERE name LIKE 'ADMINFLOW\\_%'");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
