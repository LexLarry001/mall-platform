package com.mall.gateway.shop;

import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.entity.ProductEntity;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.ShopMapper;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 店铺管理鉴权与买家店铺信息链路测试（plan Task 4 Step 1 的①-③）。
 *
 * <p>真实安全过滤链 + 真实 MySQL（mall 库，与 MerchantAuditFlowTest 同口径）；
 * TokenStore / CaptchaService mock，运行不依赖 Redis。测试数据以固定用户名写入，
 * 前后均物理清理，保证可重复执行。</p>
 */
@SpringBootTest(
        classes = ShopAuthorizationTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + ShopAuthorizationTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class ShopAuthorizationTest {

    static final String SECRET = "shop-auth-flow-test-secret-0123456789abcdef";
    private static final String MERCHANT_A = "shopflow_seller_a";
    private static final String MERCHANT_B = "shopflow_seller_b";
    private static final String MERCHANT_NO_SHOP = "shopflow_seller_noshop";

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
    private MerchantMapper merchantMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    private Long merchantAId;
    private Long merchantNoShopId;
    private Long shopAId;
    private Long shopBId;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        merchantAId = insertMerchant(MERCHANT_A);
        Long merchantBId = insertMerchant(MERCHANT_B);
        merchantNoShopId = insertMerchant(MERCHANT_NO_SHOP);
        shopAId = insertShop(merchantAId, "A 的店");
        shopBId = insertShop(merchantBId, "B 的店");
        insertProduct(shopAId, "在售A1", 2);
        insertProduct(shopAId, "在售A2", 2);
        insertProduct(shopAId, "草稿A3", 0);
        insertProduct(shopAId, "下架A4", 4);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /** ① RF2：未过审商家（shopId=null）调店铺读/写接口一律"入驻审核未通过"。 */
    @Test
    void no_shop_merchant_should_be_rejected() throws Exception {
        String token = merchantToken(merchantNoShopId, null);

        mockMvc.perform(get("/api/merchant/shop/info").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("入驻审核未通过")));

        mockMvc.perform(put("/api/merchant/shop/info")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("入驻审核未通过")));

        mockMvc.perform(post("/api/merchant/shop/biz-status")
                        .param("status", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("入驻审核未通过")));
    }

    /** ② RF1 店铺侧：商家 A 修改店铺只影响本店记录，店铺信息回读为新值。 */
    @Test
    void merchant_updates_only_own_shop() throws Exception {
        String tokenA = merchantToken(merchantAId, shopAId);

        mockMvc.perform(put("/api/merchant/shop/info")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A 的新店名\",\"logo\":\"/static/a-new.png\","
                                + "\"intro\":\"A 新简介\",\"notice\":\"A 新公告\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ShopEntity shopA = shopMapper.selectById(shopAId);
        assertThat(shopA.getName()).isEqualTo("A 的新店名");
        assertThat(shopA.getLogo()).isEqualTo("/static/a-new.png");
        assertThat(shopA.getIntro()).isEqualTo("A 新简介");
        assertThat(shopA.getNotice()).isEqualTo("A 新公告");
        assertThat(shopA.getBizStatus()).isEqualTo(1);

        ShopEntity shopB = shopMapper.selectById(shopBId);
        assertThat(shopB.getName()).isEqualTo("B 的店");
        assertThat(shopB.getIntro()).isEqualTo("原简介-B 的店");

        mockMvc.perform(get("/api/merchant/shop/info").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(shopAId))
                .andExpect(jsonPath("$.data.name").value("A 的新店名"))
                .andExpect(jsonPath("$.data.bizStatus").value(1));
    }

    /** 营业状态切换成功；非法值 400 且不落库。 */
    @Test
    void biz_status_switch_and_invalid_value_rejected() throws Exception {
        String tokenA = merchantToken(merchantAId, shopAId);

        mockMvc.perform(post("/api/merchant/shop/biz-status")
                        .param("status", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(shopMapper.selectById(shopAId).getBizStatus()).isEqualTo(0);

        mockMvc.perform(post("/api/merchant/shop/biz-status")
                        .param("status", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        assertThat(shopMapper.selectById(shopAId).getBizStatus()).isEqualTo(0);
    }

    /** ③ 买家公开接口：匿名 200，productCount 仅统计已上架（status=2）。 */
    @Test
    void buyer_shop_info_is_public() throws Exception {
        mockMvc.perform(get("/api/shop/{shopId}", shopAId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("A 的店"))
                .andExpect(jsonPath("$.data.logo").value("/static/logo.png"))
                .andExpect(jsonPath("$.data.intro").value("原简介-A 的店"))
                .andExpect(jsonPath("$.data.bizStatus").value(1))
                .andExpect(jsonPath("$.data.productCount").value(2));

        mockMvc.perform(get("/api/shop/{shopId}", 999999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("店铺不存在")));
    }

    /** 匿名调商家店铺接口 401；买家 token 调商家店铺接口 403。 */
    @Test
    void anonymous_401_and_buyer_403_on_merchant_shop_api() throws Exception {
        mockMvc.perform(get("/api/merchant/shop/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String buyerToken = jwtTokenProvider.createToken(9L, AccountType.USER, null);
        when(tokenStore.get(buyerToken)).thenReturn(new LoginUser(9L, AccountType.USER, null, null));
        when(tokenStore.isCurrent(buyerToken, AccountType.USER, 9L)).thenReturn(true);

        mockMvc.perform(get("/api/merchant/shop/info").header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private String merchantToken(Long accountId, Long shopId) {
        String token = jwtTokenProvider.createToken(accountId, AccountType.MERCHANT, shopId);
        when(tokenStore.get(token)).thenReturn(new LoginUser(accountId, AccountType.MERCHANT, shopId, null));
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, accountId)).thenReturn(true);
        return token;
    }

    private Long insertMerchant(String username) {
        MerchantEntity merchant = new MerchantEntity();
        merchant.setUsername(username);
        merchant.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOhi8Y1m0l1fH0i1p1r1l1l1l1l1l1l1");
        merchant.setContactPhone("13800000000");
        merchant.setAuditStatus(1);
        merchant.setStatus(1);
        merchantMapper.insert(merchant);
        return merchant.getId();
    }

    private Long insertShop(Long merchantId, String name) {
        ShopEntity shop = new ShopEntity();
        shop.setMerchantId(merchantId);
        shop.setName(name);
        shop.setLogo("/static/logo.png");
        shop.setIntro("原简介-" + name);
        shop.setNotice("原公告");
        shop.setBizStatus(1);
        shopMapper.insert(shop);
        return shop.getId();
    }

    private void insertProduct(Long shopId, String name, int status) {
        ProductEntity product = new ProductEntity();
        product.setShopId(shopId);
        product.setCategoryId(101L);
        product.setName(name);
        product.setMainImg("/static/p.png");
        product.setPrice(new BigDecimal("9.90"));
        product.setStatus(status);
        productMapper.insert(product);
    }

    private void cleanFixtures() {
        for (String username : List.of(MERCHANT_A, MERCHANT_B, MERCHANT_NO_SHOP)) {
            MerchantEntity merchant = merchantMapper.selectByUsername(username);
            if (merchant != null) {
                jdbcTemplate.update(
                        "DELETE FROM product WHERE shop_id IN (SELECT id FROM shop WHERE merchant_id = ?)",
                        merchant.getId());
                jdbcTemplate.update("DELETE FROM shop WHERE merchant_id = ?", merchant.getId());
                jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchant.getId());
            }
        }
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
