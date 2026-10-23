package com.mall.gateway.order;

import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.order.pay.AlipayNotifyVerifier;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步回调 + 主动查询 HTTP 链路测试（plan Task 3 验收②）。
 *
 * <p>真实安全过滤链 + 真实 MySQL + 真实 Redis；AlipayClient / AlipayNotifyVerifier mock。
 * notify 端点无鉴权（text/plain success/failure）；query 端点 ROLE_USER。
 * fixture 以 PAYNOTIFY_ 前缀命名，前后物理清理。</p>
 */
@SpringBootTest(
        classes = PaymentNotifyFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + PaymentNotifyFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "mall.alipay.app-id=2021000148635178"
        })
@AutoConfigureMockMvc
class PaymentNotifyFlowTest {

    static final String SECRET = "pay-notify-flow-test-secret-0123456789abcdef";
    private static final Long USER_A = 998_000_000_041L;
    private static final Long ADDR_A = 998_230_000_021L;
    private static final Long SHOP_A = 998_200_000_031L;
    private static final Long SPU_A = 998_210_000_031L;
    private static final Long SKU_A1 = 998_220_000_031L;

    private static final String APP_ID = "2021000148635178";
    private static final String TRADE_NO = "2026102122001488880501234567";

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
    @MockBean
    private AlipayClient alipayClient;
    @MockBean
    private AlipayNotifyVerifier notifyVerifier;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        jdbcTemplate.update("INSERT INTO user (id, username, password, status) VALUES (?, ?, 'x', 1)",
                USER_A, "PAYNOTIFY_user_a");
        jdbcTemplate.update("INSERT INTO user_address"
                        + " (id, user_id, receiver, phone, province, city, district, detail, is_default)"
                        + " VALUES (?, ?, '张三', '13800000000', '浙江省', '杭州市', '西湖区', 'PAYNOTIFY_详细地址', 1)",
                ADDR_A, USER_A);
        jdbcTemplate.update("INSERT INTO shop (id, merchant_id, name) VALUES (?, ?, 'PAYNOTIFY_甲店')",
                SHOP_A, 998_100_000_031L);
        jdbcTemplate.update("INSERT INTO product (id, shop_id, category_id, name, main_img, price, status)"
                + " VALUES (?, ?, 1, 'PAYNOTIFY_原木杯', '/img/a.jpg', 20.00, 2)", SPU_A, SHOP_A);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock, sku_img)"
                + " VALUES (?, ?, '{\"颜色\":\"原木色\"}', 20.00, 10, '/img/sku_a1.jpg')", SKU_A1, SPU_A);
        tokenA = buyerToken(USER_A);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /**
     * notify 全链路：验签通过 → success 纯文本 → 主 0→1 / 子 0→1 / 流水回填
     * （trade_no + status=1 + pay_time + notify_raw）/ ZSet 清锚点 → 重复 notify 幂等仍 success。
     * 无 token 也 200（端点无鉴权，报文真实性靠验签）。
     */
    @Test
    void notify_success_marks_paid_and_idempotent() throws Exception {
        String orderNo = submitOrderOfA(3);
        createPayFormOf(orderNo);
        when(notifyVerifier.verify(any())).thenReturn(true);

        mockMvc.perform(post("/api/pay/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", orderNo)
                        .param("trade_no", TRADE_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("app_id", APP_ID)
                        .param("total_amount", "60.00")
                        .param("gmt_payment", "2026-10-21 12:00:00")
                        .param("sign", "stub-sign"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        assertPaidState(orderNo);

        // 支付宝补发（重复通知）：仍 success 且数据不二次推进
        mockMvc.perform(post("/api/pay/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", orderNo)
                        .param("trade_no", TRADE_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("app_id", APP_ID)
                        .param("total_amount", "60.00")
                        .param("gmt_payment", "2026-10-21 12:00:00")
                        .param("sign", "stub-sign"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
        assertPaidState(orderNo);
    }

    /** 验签失败 → failure，订单数据不变。 */
    @Test
    void notify_bad_sign_returns_failure() throws Exception {
        String orderNo = submitOrderOfA(1);
        when(notifyVerifier.verify(any())).thenReturn(false);

        mockMvc.perform(post("/api/pay/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", orderNo)
                        .param("trade_no", TRADE_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("app_id", APP_ID)
                        .param("total_amount", "20.00")
                        .param("sign", "forged"))
                .andExpect(status().isOk())
                .andExpect(content().string("failure"));
        assertThat(mainStatusOf(orderNo)).isZero();
    }

    /** 金额与订单不符 → failure（防伪造报文篡改金额），订单数据不变。 */
    @Test
    void notify_amount_mismatch_returns_failure() throws Exception {
        String orderNo = submitOrderOfA(1);
        when(notifyVerifier.verify(any())).thenReturn(true);

        mockMvc.perform(post("/api/pay/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", orderNo)
                        .param("trade_no", TRADE_NO)
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("app_id", APP_ID)
                        .param("total_amount", "0.01")
                        .param("sign", "stub-sign"))
                .andExpect(status().isOk())
                .andExpect(content().string("failure"));
        assertThat(mainStatusOf(orderNo)).isZero();
    }

    /**
     * query 兜底链路：下单 → 支付宝查询 TRADE_SUCCESS → markPaid → data=1 →
     * 主/子/流水/ZSet 断言；未登录 401。
     */
    @Test
    void query_trade_success_marks_paid() throws Exception {
        String orderNo = submitOrderOfA(3);
        createPayFormOf(orderNo);
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        // isSuccess() 由 subCode 为空推导（SDK 语义），不设即视为成功
        response.setTradeStatus("TRADE_SUCCESS");
        response.setTradeNo(TRADE_NO);
        response.setSendPayDate(new java.util.Date());
        response.setBody("{\"alipay_trade_query_response\":{\"trade_status\":\"TRADE_SUCCESS\"}}");
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/user/pay/{orderNo}/query", orderNo))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/user/pay/{orderNo}/query", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));

        assertPaidState(orderNo);
    }

    /** query 查询未付款（WAIT_BUYER_PAY）：data=0，订单不推进。 */
    @Test
    void query_wait_buyer_pay_stays_unpaid() throws Exception {
        String orderNo = submitOrderOfA(1);
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setTradeStatus("WAIT_BUYER_PAY");
        when(alipayClient.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/user/pay/{orderNo}/query", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(0));
        assertThat(mainStatusOf(orderNo)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 真实链路前置：买家先 createPayForm 落 payment_record 流水，支付宝侧才可能发生付款。
     * notify/query 用例一律先走本方法，保证流水存在（markPaid 只做回填不做插入）。
     */
    private void createPayFormOf(String orderNo) throws Exception {
        AlipayTradePagePayResponse pageResponse = new AlipayTradePagePayResponse();
        pageResponse.setBody("<form>sandbox</form>");
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(pageResponse);
        mockMvc.perform(post("/api/user/pay/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 支付成功终态断言：主=1、子=1、流水回填齐、ZSet 锚点清除。 */
    private void assertPaidState(String orderNo) {
        assertThat(mainStatusOf(orderNo)).isEqualTo(1);
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE order_no = ?", Long.class, orderNo);
        Integer paidSubs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sub_order WHERE order_id = ? AND status = 1",
                Integer.class, orderId);
        assertThat(paidSubs).isEqualTo(1);
        var record = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_record WHERE order_no = ?", orderNo);
        assertThat(((Number) record.get("status")).intValue()).isEqualTo(1);
        assertThat((String) record.get("trade_no")).isEqualTo(TRADE_NO);
        assertThat(record.get("pay_time")).isNotNull();
        assertThat((String) record.get("notify_raw")).isNotBlank();
        assertThat(redis.opsForZSet().score("mall:order:timeout", orderNo)).isNull();
    }

    private int mainStatusOf(String orderNo) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE order_no = ?", Integer.class, orderNo);
        return status == null ? -1 : status;
    }

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
        redis.delete("mall:order:submit:" + USER_A);
        redis.delete("mall:order:timeout");
        jdbcTemplate.update("DELETE pr FROM payment_record pr"
                + " JOIN `order` o ON pr.order_no = o.order_no WHERE o.user_id = ?", USER_A);
        jdbcTemplate.update("DELETE oi FROM order_item oi"
                + " JOIN sub_order s ON oi.sub_order_id = s.id"
                + " JOIN `order` o ON s.order_id = o.id WHERE o.user_id = ?", USER_A);
        jdbcTemplate.update("DELETE s FROM sub_order s"
                + " JOIN `order` o ON s.order_id = o.id WHERE o.user_id = ?", USER_A);
        jdbcTemplate.update("DELETE FROM `order` WHERE user_id = ?", USER_A);
        jdbcTemplate.update("DELETE FROM user_address WHERE id = ?", ADDR_A);
        jdbcTemplate.update("DELETE FROM product_sku WHERE id = ?", SKU_A1);
        jdbcTemplate.update("DELETE FROM product WHERE id = ?", SPU_A);
        jdbcTemplate.update("DELETE FROM shop WHERE id = ?", SHOP_A);
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'PAYNOTIFY\\_%'");
    }
}
