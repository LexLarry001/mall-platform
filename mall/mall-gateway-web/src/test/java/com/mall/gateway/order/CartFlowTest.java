package com.mall.gateway.order;

import com.mall.common.exception.GlobalExceptionHandler;
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

import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 购物车 HTTP 链路测试（plan Task 2 验收②）。
 *
 * <p>真实安全过滤链 + 真实 MySQL（mall 库）+ 真实 Redis（购物车本体）；
 * TokenStore / CaptchaService mock。fixture 以 CARTFLOW_ 前缀与固定位 ID 命名，
 * 前后物理清理（含 Redis 购物车键），保证可重复执行。</p>
 */
@SpringBootTest(
        classes = CartFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + CartFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class CartFlowTest {

    static final String SECRET = "cart-flow-test-secret-0123456789abcdefgh";
    private static final Long USER_A = 998_000_000_011L;
    private static final Long USER_B = 998_000_000_012L;

    private static final Long SHOP_ID = 998_200_000_001L;
    private static final Long SPU_ON = 998_210_000_001L;
    private static final Long SPU_OFF = 998_210_000_002L;
    private static final Long SKU_1 = 998_220_000_001L;
    private static final Long SKU_STOCK_ZERO = 998_220_000_002L;
    private static final Long SKU_OFF_SHELF = 998_220_000_003L;

    @SpringBootApplication(scanBasePackages = {
            "com.mall.merchant", "com.mall.admin", "com.mall.product", "com.mall.order",
            "com.mall.gateway.auth", "com.mall.gateway.config", "com.mall.ai", "com.mall.security"})
    @MapperScan({"com.mall.merchant.mapper", "com.mall.product.mapper",
            "com.mall.admin.mapper", "com.mall.user.mapper", "com.mall.order.mapper"})
    @Import(GlobalExceptionHandler.class)
    @EnableMongoRepositories(basePackages = "com.mall")
    static class TestApp {
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    private String tokenA;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertUser(USER_A, "CARTFLOW_user_a");
        insertUser(USER_B, "CARTFLOW_user_b");
        jdbcTemplate.update("INSERT INTO shop (id, merchant_id, name) VALUES (?, ?, 'CARTFLOW_木艺小店')",
                SHOP_ID, 998_100_000_001L);
        jdbcTemplate.update("INSERT INTO product (id, shop_id, category_id, name, main_img, price, status)"
                        + " VALUES (?, ?, 1, 'CARTFLOW_原木杯', '/img/cup.jpg', 19.90, 2)", SPU_ON, SHOP_ID);
        jdbcTemplate.update("INSERT INTO product (id, shop_id, category_id, name, main_img, price, status)"
                + " VALUES (?, ?, 1, 'CARTFLOW_下架品', '/img/off.jpg', 9.90, 4)", SPU_OFF, SHOP_ID);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock, sku_img)"
                + " VALUES (?, ?, '{\"颜色\":\"原木色\"}', 19.90, 10, '/img/sku1.jpg')", SKU_1, SPU_ON);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock)"
                + " VALUES (?, ?, '{\"颜色\":\"胡桃色\"}', 29.90, 0)", SKU_STOCK_ZERO, SPU_ON);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock)"
                + " VALUES (?, ?, '{}', 9.90, 5)", SKU_OFF_SHELF, SPU_OFF);
        tokenA = buyerToken(USER_A);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /**
     * 全链路：加购 → 同款合并 → 库存 0 失效打标 → 改数量/勾选 → 移除。
     */
    @Test
    void cart_full_chain() throws Exception {
        // 加购 sku1 x2
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_1 + ",\"quantity\":2}"))
                .andExpect(jsonPath("$.code").value(0));

        // 列表：1 项，数量 2，默认勾选，联查字段齐全
        mockMvc.perform(get("/api/user/cart").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuId").value(SKU_1))
                .andExpect(jsonPath("$.data[0].spuId").value(SPU_ON))
                .andExpect(jsonPath("$.data[0].shopName").value("CARTFLOW_木艺小店"))
                .andExpect(jsonPath("$.data[0].productName").value("CARTFLOW_原木杯"))
                .andExpect(jsonPath("$.data[0].skuImg").value("/img/sku1.jpg"))
                .andExpect(jsonPath("$.data[0].price").value(19.9))
                .andExpect(jsonPath("$.data[0].stock").value(10))
                .andExpect(jsonPath("$.data[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].checked").value(true))
                .andExpect(jsonPath("$.data[0].invalid").value(false));

        // 同款合并：数量 2+1=3
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_1 + ",\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(0));

        // 库存 0 的 sku2 也可加购，但列表打标 invalid
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_STOCK_ZERO + ",\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/user/cart").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].skuId").value(SKU_1))
                .andExpect(jsonPath("$.data[0].quantity").value(3))
                .andExpect(jsonPath("$.data[0].invalid").value(false))
                .andExpect(jsonPath("$.data[1].skuId").value(SKU_STOCK_ZERO))
                .andExpect(jsonPath("$.data[1].invalid").value(true));

        // 改数量 + 取消勾选
        mockMvc.perform(put("/api/user/cart/{skuId}", SKU_1)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5,\"checked\":false}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/user/cart").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data[0].quantity").value(5))
                .andExpect(jsonPath("$.data[0].checked").value(false));

        // 移除 sku2
        mockMvc.perform(delete("/api/user/cart/{skuId}", SKU_STOCK_ZERO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/user/cart").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuId").value(SKU_1));
    }

    /** 加购下架商品 / 不存在 SKU：业务错误。 */
    @Test
    void add_off_shelf_or_missing_rejected() throws Exception {
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_OFF_SHELF + ",\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("商品不存在或已下架")));

        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":999999999,\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("商品不存在或已下架")));
    }

    /** 购物车隔离：B 看不到 A 的条目。 */
    @Test
    void cart_isolated_per_user() throws Exception {
        String tokenB = buyerToken(USER_B);
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_1 + ",\"quantity\":2}"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/user/cart").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** 未登录 401；商家 token 访问买家接口 403。 */
    @Test
    void cart_requires_buyer_role() throws Exception {
        mockMvc.perform(get("/api/user/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String merchantToken = jwtTokenProvider.createToken(USER_B, AccountType.MERCHANT, null);
        when(tokenStore.get(merchantToken)).thenReturn(new LoginUser(USER_B, AccountType.MERCHANT, null, null));
        when(tokenStore.isCurrent(merchantToken, AccountType.MERCHANT, USER_B)).thenReturn(true);

        mockMvc.perform(get("/api/user/cart").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 参数校验：数量 0 → 400。 */
    @Test
    void quantity_zero_rejected() throws Exception {
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_1 + ",\"quantity\":0}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    private String buyerToken(Long userId) {
        String token = jwtTokenProvider.createToken(userId, AccountType.USER, null);
        when(tokenStore.get(token)).thenReturn(new LoginUser(userId, AccountType.USER, null, null));
        when(tokenStore.isCurrent(token, AccountType.USER, userId)).thenReturn(true);
        return token;
    }

    private void insertUser(Long id, String username) {
        jdbcTemplate.update("INSERT INTO user (id, username, password, status) VALUES (?, ?, 'x', 1)", id, username);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void cleanFixtures() {
        redis.delete("mall:cart:" + USER_A);
        redis.delete("mall:cart:" + USER_B);
        jdbcTemplate.update("DELETE FROM product_sku WHERE id IN (?, ?, ?)", SKU_1, SKU_STOCK_ZERO, SKU_OFF_SHELF);
        jdbcTemplate.update("DELETE FROM product WHERE id IN (?, ?)", SPU_ON, SPU_OFF);
        jdbcTemplate.update("DELETE FROM shop WHERE id = ?", SHOP_ID);
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'CARTFLOW\\_%'");
    }
}
