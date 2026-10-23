package com.mall.gateway.category;

import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.product.entity.CategoryEntity;
import com.mall.product.entity.ProductEntity;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 类目管理权限与买家类目导航链路测试（plan Task 5 Step 1/4）。
 *
 * <p>真实安全过滤链 + 真实 MySQL + 真实 Redis（mall-redis 容器，验证缓存键的写入与删除）；
 * TokenStore / CaptchaService mock。测试数据以 {@code CATFLOW_} 前缀命名，前后物理清理，
 * 类目树缓存键 {@code mall:category:tree} 每个用例前后均清除，保证可重复执行。</p>
 */
@SpringBootTest(
        classes = CategoryAuthorizationTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + CategoryAuthorizationTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class CategoryAuthorizationTest {

    static final String SECRET = "category-auth-flow-test-secret-0123456789abcdef";
    private static final String TREE_CACHE_KEY = "mall:category:tree";

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
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    @BeforeEach
    void setUp() {
        cleanFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /** 买家导航：匿名 200；停用类目不出现；首次查询后缓存键已写入。 */
    @Test
    void buyer_tree_is_public_and_hides_disabled() throws Exception {
        insertCategory(null, "CATFLOW_启用类目", 1, 1);
        insertCategory(null, "CATFLOW_停用类目", 2, 0);

        mockMvc.perform(get("/api/category/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[*].name").value(hasItem("CATFLOW_启用类目")))
                .andExpect(jsonPath("$.data[*].name").value(not(hasItem("CATFLOW_停用类目"))));

        assertThat(stringRedisTemplate.hasKey(TREE_CACHE_KEY)).isTrue();
    }

    /** 平台类目接口鉴权：无权限码 403；有权限码 200 且全量含停用。 */
    @Test
    void admin_tree_requires_permission_and_contains_disabled() throws Exception {
        insertCategory(null, "CATFLOW_停用类目", 1, 0);

        mockMvc.perform(get("/api/admin/category/tree")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken(Set.of("system:user:list")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/admin/category")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken(Set.of("product:category:list"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":0,\"name\":\"CATFLOW_无权新增\",\"sort\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/category/tree")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken(Set.of("product:category:list")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[*].name").value(hasItem("CATFLOW_停用类目")));

        mockMvc.perform(get("/api/admin/category/tree"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** 写操作链路：新增/编辑/停用/删除，每一步后缓存键被删、买家树读到的都是新值（RF5）。 */
    @Test
    void write_ops_evict_cache_and_buyer_tree_reflects_changes() throws Exception {
        String admin = bearer(adminToken(Set.of(
                "product:category:list", "product:category:add",
                "product:category:edit", "product:category:delete")));

        // 预热缓存
        mockMvc.perform(get("/api/category/tree")).andExpect(jsonPath("$.code").value(0));
        assertThat(stringRedisTemplate.hasKey(TREE_CACHE_KEY)).isTrue();

        // 新增 → 缓存被删，买家树重建后含新类目
        String createBody = mockMvc.perform(post("/api/admin/category")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":0,\"name\":\"CATFLOW_新类目\",\"sort\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Long categoryId = ((Number) com.jayway.jsonpath.JsonPath.read(createBody, "$.data")).longValue();
        assertThat(stringRedisTemplate.hasKey(TREE_CACHE_KEY)).isFalse();

        mockMvc.perform(get("/api/category/tree"))
                .andExpect(jsonPath("$.data[*].name").value(hasItem("CATFLOW_新类目")));
        assertThat(stringRedisTemplate.hasKey(TREE_CACHE_KEY)).isTrue();

        // 编辑 → 买家树读到新名称
        mockMvc.perform(put("/api/admin/category/{id}", categoryId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CATFLOW_改名后\",\"sort\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(stringRedisTemplate.hasKey(TREE_CACHE_KEY)).isFalse();
        mockMvc.perform(get("/api/category/tree"))
                .andExpect(jsonPath("$.data[*].name").value(hasItem("CATFLOW_改名后")));

        // 停用 → 买家树不再展示，管理端树仍可见
        mockMvc.perform(post("/api/admin/category/{id}/status", categoryId)
                        .param("status", "0")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/category/tree"))
                .andExpect(jsonPath("$.data[*].name").value(not(hasItem("CATFLOW_改名后"))));
        mockMvc.perform(get("/api/admin/category/tree").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.data[*].name").value(hasItem("CATFLOW_改名后")));

        // 删除 → 管理端树也不可见
        mockMvc.perform(delete("/api/admin/category/{id}", categoryId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/admin/category/tree").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.data[*].name").value(not(hasItem("CATFLOW_改名后"))));
    }

    /** 删除保护（RF4）链路：有子类目或有商品引用 → 业务错误且记录仍在。 */
    @Test
    void delete_protection_over_http() throws Exception {
        String admin = bearer(adminToken(Set.of("product:category:delete")));

        Long parentId = insertCategory(null, "CATFLOW_父类目", 1, 1);
        Long childId = insertCategory(parentId, "CATFLOW_子类目", 1, 1);
        mockMvc.perform(delete("/api/admin/category/{id}", parentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("子类目")));
        assertThat(categoryMapper.selectById(parentId)).isNotNull();

        jdbcTemplate.update("DELETE FROM category WHERE id = ?", childId);

        ProductEntity product = new ProductEntity();
        product.setShopId(1L);
        product.setCategoryId(parentId);
        product.setName("CATFLOW_引用商品");
        product.setMainImg("/static/p.png");
        product.setPrice(new BigDecimal("9.90"));
        product.setStatus(0);
        productMapper.insert(product);

        mockMvc.perform(delete("/api/admin/category/{id}", parentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("存在商品")));
        assertThat(categoryMapper.selectById(parentId)).isNotNull();
    }

    private String adminToken(Set<String> permissions) {
        String token = jwtTokenProvider.createToken(1L, AccountType.ADMIN, null);
        when(tokenStore.get(token)).thenReturn(new LoginUser(1L, AccountType.ADMIN, null, permissions));
        when(tokenStore.isCurrent(token, AccountType.ADMIN, 1L)).thenReturn(true);
        return token;
    }

    private Long insertCategory(Long parentId, String name, int sort, int status) {
        CategoryEntity entity = new CategoryEntity();
        entity.setParentId(parentId == null ? 0L : parentId);
        entity.setName(name);
        entity.setSort(sort);
        entity.setStatus(status);
        categoryMapper.insert(entity);
        return entity.getId();
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM product WHERE name LIKE 'CATFLOW\\_%'");
        jdbcTemplate.update("DELETE FROM category WHERE name LIKE 'CATFLOW\\_%'");
        stringRedisTemplate.delete(TREE_CACHE_KEY);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
