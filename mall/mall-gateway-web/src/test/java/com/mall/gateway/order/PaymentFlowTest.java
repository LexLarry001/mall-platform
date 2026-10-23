package com.mall.gateway.order;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付下单 HTTP 链路测试（plan Task 2 验收②）。
 *
 * <p>真实安全过滤链 + 真实 MySQL（mall 库）+ 真实 Redis；AlipayClient mock（不依赖真实沙箱密钥）。
 * fixture 以 PAYFLOW_ 前缀与固定位 ID 命名，前后物理清理（含 Redis 键），保证可重复执行。</p>
 */
@SpringBootTest(
        classes = PaymentFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + PaymentFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "mall.alipay.notify-url=http://gateway.test/api/pay/notify",
                "mall.alipay.return-url=http://localhost:5173/pay/result"
        })
@AutoConfigureMockMvc
class PaymentFlowTest {

    static final String SECRET = "payment-flow-test-secret-0123456789abcdef";
    private static final Long USER_A = 998_000_000_031L;
    private static final Long USER_B = 998_000_000_032L;

    private static final Long ADDR_A = 998_230_000_011L;

    private static final Long SHOP_A = 998_200_000_021L;
    private static final Long SPU_A = 998_210_000_021L;
    private static final Long SKU_A1 = 998_220_000_021L;

    private static final String SANDBOX_FORM = "<form id=\"alipayForm\" action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\">sandbox</form>";

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
    /** 沙箱密钥不进测试：网关客户端一律 mock（plan 验证策略）。 */
    @MockBean
    private AlipayClient alipayClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbcTemplate.update("INSERT INTO user (id, username, password, status) VALUES (?, ?, 'x', 1)",
                USER_A, "PAYFLOW_user_a");
        jdbcTemplate.update("INSERT INTO user (id, username, password, status) VALUES (?, ?, 'x', 1)",
                USER_B, "PAYFLOW_user_b");
        jdbcTemplate.update("INSERT INTO user_address"
                        + " (id, user_id, receiver, phone, province, city, district, detail, is_default)"
                        + " VALUES (?, ?, '张三', '13800000000', '浙江省', '杭州市', '西湖区', 'PAYFLOW_详细地址', 1)",
                ADDR_A, USER_A);
        jdbcTemplate.update("INSERT INTO shop (id, merchant_id, name) VALUES (?, ?, 'PAYFLOW_甲店')",
                SHOP_A, 998_100_000_021L);
        jdbcTemplate.update("INSERT INTO product (id, shop_id, category_id, name, main_img, price, status)"
                + " VALUES (?, ?, 1, 'PAYFLOW_原木杯', '/img/a.jpg', 20.00, 2)", SPU_A, SHOP_A);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock, sku_img)"
                + " VALUES (?, ?, '{\"颜色\":\"原木色\"}', 20.00, 10, '/img/sku_a1.jpg')", SKU_A1, SPU_A);
        tokenA = buyerToken(USER_A);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /**
     * 下单 → 去支付：返回沙箱表单 HTML + payment_record 落库（金额快照 60.00、status=0）→
     * 再次去支付幂等（仍 1 条流水，支付宝侧同 out_trade_no 可重复唤起）。
     */
    @Test
    void create_pay_form_chain_and_idempotent_record() throws Exception {
        String orderNo = submitOrderOfA(3);
        stubPageExecute(SANDBOX_FORM);

        // 第一次发起支付
        mockMvc.perform(post("/api/user/pay/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(SANDBOX_FORM));

        var record = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_record WHERE order_no = ?", orderNo);
        assertThat((BigDecimal) record.get("amount")).isEqualByComparingTo("60.00");
        assertThat(((Number) record.get("status")).intValue()).isZero();
        assertThat(record.get("trade_no")).isNull();

        // 重复发起：流水仍 1 条（uk_order_no 幂等），表单正常返回
        mockMvc.perform(post("/api/user/pay/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(SANDBOX_FORM));
        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE order_no = ?", Integer.class, orderNo);
        assertThat(recordCount).isEqualTo(1);
    }

    /** 他人订单去支付：业务错误"订单不存在"，不泄露存在性。 */
    @Test
    void pay_order_of_other_user_rejected() throws Exception {
        String orderNo = submitOrderOfA(1);
        String tokenB = buyerToken(USER_B);

        mockMvc.perform(post("/api/user/pay/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("订单不存在"));
        assertThat(recordCountOf(orderNo)).isZero();
    }

    /** 已取消订单去支付：业务错误"当前状态不可支付"。 */
    @Test
    void pay_cancelled_order_rejected() throws Exception {
        String orderNo = submitOrderOfA(1);
        mockMvc.perform(post("/api/user/order/{orderNo}/cancel", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/user/pay/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("当前状态不可支付"));
        assertThat(recordCountOf(orderNo)).isZero();
    }

    /** 支付宝网关异常：统一业务文案"支付服务暂不可用，请稍后重试"（流水已落库，可重试）。 */
    @Test
    void pay_gateway_error_wrapped() throws Exception {
        String orderNo = submitOrderOfA(1);
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class)))
                .thenThrow(new AlipayApiException("isp.RATE_LIMIT"));

        mockMvc.perform(post("/api/user/pay/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("支付服务暂不可用，请稍后重试"));
        assertThat(recordCountOf(orderNo)).isEqualTo(1);
    }

    /** 未登录 401；不存在的订单号 → "订单不存在"。 */
    @Test
    void pay_requires_login_and_existing_order() throws Exception {
        mockMvc.perform(post("/api/user/pay/{orderNo}", "M_NOT_EXIST"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/user/pay/{orderNo}", "M_NOT_EXIST")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    // ------------------------------------------------------------------ helpers

    /** USER_A 加购 SKU_A1 x quantity 并下单，返回主订单号。 */
    private String submitOrderOfA(int quantity) throws Exception {
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + SKU_A1 + ",\"quantity\":" + quantity + "}"))
                .andExpect(jsonPath("$.code").value(0));
        MvcResult result = mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_A + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(startsWith("M")))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("data").asText();
    }

    private void stubPageExecute(String form) throws AlipayApiException {
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse();
        response.setBody(form);
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(response);
    }

    private int recordCountOf(String orderNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE order_no = ?", Integer.class, orderNo);
        return count == null ? 0 : count;
    }

    private String buyerToken(Long userId) {
        String token = jwtTokenProvider.createToken(userId, AccountType.USER, null);
        when(tokenStore.get(token)).thenReturn(new LoginUser(userId, AccountType.USER, null, null));
        when(tokenStore.isCurrent(token, AccountType.USER, userId)).thenReturn(true);
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void cleanFixtures() {
        redis.delete("mall:cart:" + USER_A);
        redis.delete("mall:cart:" + USER_B);
        redis.delete("mall:order:submit:" + USER_A);
        redis.delete("mall:order:submit:" + USER_B);
        redis.delete("mall:order:timeout");
        jdbcTemplate.update("DELETE pr FROM payment_record pr"
                + " JOIN `order` o ON pr.order_no = o.order_no WHERE o.user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE oi FROM order_item oi"
                + " JOIN sub_order s ON oi.sub_order_id = s.id"
                + " JOIN `order` o ON s.order_id = o.id WHERE o.user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE s FROM sub_order s"
                + " JOIN `order` o ON s.order_id = o.id WHERE o.user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE FROM `order` WHERE user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE FROM user_address WHERE id = ?", ADDR_A);
        jdbcTemplate.update("DELETE FROM product_sku WHERE id = ?", SKU_A1);
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", SPU_A);
        jdbcTemplate.update("DELETE FROM shop WHERE id = ?", SHOP_A);
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'PAYFLOW\\_%'");
    }
}
