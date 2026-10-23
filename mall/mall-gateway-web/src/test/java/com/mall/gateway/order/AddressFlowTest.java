package com.mall.gateway.order;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收货地址 HTTP 链路测试（plan Task 1 验收②）。
 *
 * <p>真实安全过滤链 + 真实 MySQL（mall 库）；TokenStore / CaptchaService mock，运行不依赖 Redis。
 * 测试数据以 ADDRFLOW_ 前缀命名，前后物理清理，保证可重复执行。</p>
 */
@SpringBootTest(
        classes = AddressFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + AddressFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class AddressFlowTest {

    static final String SECRET = "address-flow-test-secret-0123456789abcdefgh";
    private static final Long USER_A = 998_000_000_001L;
    private static final Long USER_B = 998_000_000_002L;

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

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertUser(USER_A, "ADDRFLOW_user_a");
        insertUser(USER_B, "ADDRFLOW_user_b");
        tokenA = buyerToken(USER_A);
        tokenB = buyerToken(USER_B);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /**
     * 全链路：建默认 → 建非默认 → 列表默认在前 → 换默认 → 编辑 → 删除默认后不落新默认。
     */
    @Test
    void address_crud_full_chain() throws Exception {
        Long addr1 = createAddress(tokenA, "ADDRFLOW_张三", true);
        Long addr2 = createAddress(tokenA, "ADDRFLOW_李四", false);

        // 列表：默认在前
        mockMvc.perform(get("/api/user/address").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(addr1))
                .andExpect(jsonPath("$.data[0].isDefault").value(1))
                .andExpect(jsonPath("$.data[1].id").value(addr2))
                .andExpect(jsonPath("$.data[1].isDefault").value(0));

        // 换默认：addr2 变默认，addr1 自动取消
        mockMvc.perform(post("/api/user/address/{id}/default", addr2)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/user/address").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data[0].id").value(addr2))
                .andExpect(jsonPath("$.data[0].isDefault").value(1))
                .andExpect(jsonPath("$.data[1].id").value(addr1))
                .andExpect(jsonPath("$.data[1].isDefault").value(0));

        // 编辑 addr1 并顺带设为默认（PUT 全量语义）：addr2 自动取消
        mockMvc.perform(put("/api/user/address/{id}", addr1)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressJson("ADDRFLOW_张三改", true)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/user/address").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data[0].id").value(addr1))
                .andExpect(jsonPath("$.data[0].receiver").value("ADDRFLOW_张三改"))
                .andExpect(jsonPath("$.data[0].isDefault").value(1))
                .andExpect(jsonPath("$.data[1].isDefault").value(0));

        // 删除默认 addr1：addr2 仍是唯一地址且不落新默认
        mockMvc.perform(delete("/api/user/address/{id}", addr1)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/user/address").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(addr2))
                .andExpect(jsonPath("$.data[0].isDefault").value(0));
    }

    /** 未登录 401；商家 token 访问买家接口 403。 */
    @Test
    void address_requires_buyer_role() throws Exception {
        mockMvc.perform(get("/api/user/address"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String merchantToken = jwtTokenProvider.createToken(USER_B, AccountType.MERCHANT, null);
        when(tokenStore.get(merchantToken)).thenReturn(new LoginUser(USER_B, AccountType.MERCHANT, null, null));
        when(tokenStore.isCurrent(merchantToken, AccountType.MERCHANT, USER_B)).thenReturn(true);

        mockMvc.perform(get("/api/user/address").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 越权：B 操作 A 的地址，设默认/编辑/删除一律"地址不存在"。 */
    @Test
    void foreign_address_ops_rejected() throws Exception {
        Long addrOfA = createAddress(tokenA, "ADDRFLOW_张三", true);

        mockMvc.perform(post("/api/user/address/{id}/default", addrOfA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("地址不存在")));

        mockMvc.perform(put("/api/user/address/{id}", addrOfA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressJson("ADDRFLOW_劫持", true)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("地址不存在")));

        mockMvc.perform(delete("/api/user/address/{id}", addrOfA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("地址不存在")));

        // A 的地址仍在且仍是默认
        mockMvc.perform(get("/api/user/address").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].receiver").value("ADDRFLOW_张三"))
                .andExpect(jsonPath("$.data[0].isDefault").value(1));
    }

    /** 参数校验：收货人空白 → 400。 */
    @Test
    void blank_receiver_rejected() throws Exception {
        mockMvc.perform(post("/api/user/address")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressJson("  ", false)))
                .andExpect(jsonPath("$.code").value(400));
    }

    private Long createAddress(String token, String receiver, boolean isDefault) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/address")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressJson(receiver, isDefault)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data")).longValue();
    }

    private String addressJson(String receiver, boolean isDefault) {
        return """
                {"receiver":"%s","phone":"13800000001","province":"浙江省","city":"杭州市",
                 "district":"西湖区","detail":"文三路 100 号","isDefault":%s}
                """.formatted(receiver, isDefault);
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
        jdbcTemplate.update("DELETE FROM user_address WHERE receiver LIKE 'ADDRFLOW\\_%'");
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'ADDRFLOW\\_%'");
        assertThat(true).as("cleanup done").isTrue();
    }
}
