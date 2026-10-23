package com.mall.gateway.order;

import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.order.logistics.LogisticsTraceRepository;
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
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 物流全链路 HTTP 测试（plan Task 2-4 验收：发货 / 推进 / 轨迹 / 确认收货）。
 *
 * <p>真实安全过滤链 + 真实 MySQL + 真实 MongoDB（ManualLogisticsProvider 真跑）。
 * fixture 以 LOGFLOW_ 前缀命名，前后物理清理，保证可重复执行。</p>
 */
@SpringBootTest(
        classes = LogisticsFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + LogisticsFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "mall.logistics.provider=manual"
        })
@AutoConfigureMockMvc
class LogisticsFlowTest {

    static final String SECRET = "logistics-flow-test-secret-0123456789abcdef";
    private static final Long BUYER_A = 998_000_000_041L;
    private static final Long BUYER_B = 998_000_000_042L;
    private static final Long SHOP_A = 998_200_000_041L;
    private static final Long SHOP_B = 998_200_000_042L;

    private static final Long ORDER_ID = 998_300_000_041L;
    private static final Long SUB_ID = 998_310_000_041L;
    private static final Long ITEM_ID = 998_320_000_041L;

    private static final String ORDER_NO = "LOGFLOW_M0001";
    private static final String SUB_NO = "LOGFLOW_S0001";

    @SpringBootApplication(scanBasePackages = {
            "com.mall.merchant", "com.mall.admin", "com.mall.product", "com.mall.order",
            "com.mall.gateway.auth", "com.mall.gateway.config", "com.mall.gateway.order",
            "com.mall.gateway.logistics", "com.mall.ai", "com.mall.security"})
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
    private LogisticsTraceRepository traceRepository;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    private String buyerToken;
    private String otherBuyerToken;
    private String merchantToken;
    private String otherMerchantToken;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertOrder();
        insertSubOrder(1);
        insertOrderItem();

        buyerToken = token(BUYER_A, AccountType.USER, null);
        otherBuyerToken = token(BUYER_B, AccountType.USER, null);
        merchantToken = token(BUYER_A, AccountType.MERCHANT, SHOP_A);
        otherMerchantToken = token(BUYER_B, AccountType.MERCHANT, SHOP_B);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /**
     * 全链路：商家看单 → 发货 → 越权/重复发货被拒 → 买家查轨迹 → 推进三段 → 确认收货 → 主订单聚合。
     */
    @Test
    void fulfillment_full_chain() throws Exception {
        // 1. 商家订单列表：只看到本店子订单，含买家快照与订单项
        mockMvc.perform(get("/api/merchant/order/list").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].subOrderNo").value(SUB_NO))
                .andExpect(jsonPath("$.data[0].orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$.data[0].status").value(1))
                .andExpect(jsonPath("$.data[0].receiver").value("LOGFLOW_收货人"))
                .andExpect(jsonPath("$.data[0].items[0].productName").value("LOGFLOW_木椅"));

        // 他店商家看不到
        mockMvc.perform(get("/api/merchant/order/list").header(HttpHeaders.AUTHORIZATION, bearer(otherMerchantToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 2. 他店商家发货被拒
        mockMvc.perform(post("/api/merchant/ship/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherMerchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipJson()))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));

        // 3. 本店发货成功
        String shipmentNo = postForData("/api/merchant/ship/" + SUB_NO, merchantToken, shipJson());
        assertThat(shipmentNo).startsWith("SH");

        assertThat(subStatus()).isEqualTo(2);
        assertThat(shipmentStatus()).isEqualTo("SHIPPED");
        assertThat(traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo)).hasSize(1);

        // 重复发货：子订单已 2 → 当前状态不可发货
        mockMvc.perform(post("/api/merchant/ship/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipJson()))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("当前状态不可发货")));

        // 4. 买家查发货单 + 轨迹
        mockMvc.perform(get("/api/user/logistics/shipment/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.shipmentNo").value(shipmentNo))
                .andExpect(jsonPath("$.data.carrierName").value("圆通"))
                .andExpect(jsonPath("$.data.trackingNo").value("YTO123456789"));

        mockMvc.perform(get("/api/user/logistics/trace/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("SHIPPED"));

        // 他人订单：一律订单不存在
        mockMvc.perform(get("/api/user/logistics/shipment/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherBuyerToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));
        mockMvc.perform(get("/api/user/logistics/trace/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherBuyerToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));

        // 5. 商家推进：运输中 → 派送中
        mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desc\":\"包裹已到达上海分拣中心\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("IN_TRANSIT"));

        // 他店商家推进被拒
        mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherMerchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desc\":\"越权\"}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));

        mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desc\":\"快递员正在派送\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("OUT_FOR_DELIVERY"));

        assertThat(shipmentStatus()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo)).hasSize(3);

        // 6. 买家确认收货：子订单 3 + 主订单 2
        mockMvc.perform(post("/api/user/logistics/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherBuyerToken))
                        .param("orderNo", ORDER_NO)
                        .param("subOrderNo", SUB_NO))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));

        mockMvc.perform(post("/api/user/logistics/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken))
                        .param("orderNo", ORDER_NO)
                        .param("subOrderNo", SUB_NO))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(subStatus()).isEqualTo(3);
        assertThat(orderStatus()).isEqualTo(2);

        // 重复确认收货被拒
        mockMvc.perform(post("/api/user/logistics/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken))
                        .param("orderNo", ORDER_NO)
                        .param("subOrderNo", SUB_NO))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("当前状态不可确认收货")));
    }

    /** 商家推进到 DELIVERED 后再推进必须被拒。 */
    @Test
    void push_status_rejects_after_delivered() throws Exception {
        String shipmentNo = postForData("/api/merchant/ship/" + SUB_NO, merchantToken, shipJson());
        for (String expected : List.of("IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")) {
            mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                            .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"desc\":\"推进\"}"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").value(expected));
        }

        mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desc\":\"再推\"}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("物流已签收")));
    }

    // ------------------------------------------------------------------ helpers

    private String token(Long accountId, AccountType type, Long shopId) {
        String token = jwtTokenProvider.createToken(accountId, type, shopId);
        when(tokenStore.get(token)).thenReturn(new LoginUser(accountId, type, shopId, null));
        when(tokenStore.isCurrent(token, type, accountId)).thenReturn(true);
        return token;
    }

    private String postForData(String url, String token, String body) throws Exception {
        String response = mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String shipJson() {
        return "{\"carrierCode\":\"YTO\",\"carrierName\":\"圆通\",\"trackingNo\":\"YTO123456789\"}";
    }

    private Integer subStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM sub_order WHERE id = ?", Integer.class, SUB_ID);
    }

    private Integer orderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, ORDER_ID);
    }

    private String shipmentStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT current_status FROM shipment WHERE sub_order_id = ?", String.class, SUB_ID);
    }

    private void insertOrder() {
        jdbcTemplate.update("INSERT INTO `order` (id, order_no, user_id, total_amount, pay_amount, status, "
                        + "receiver, phone, full_address, expire_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 30 MINUTE))",
                ORDER_ID, ORDER_NO, BUYER_A, 199.00, 199.00, 1,
                "LOGFLOW_收货人", "13800000000", "上海市浦东新区世纪大道 1 号");
    }

    private void insertSubOrder(int status) {
        jdbcTemplate.update("INSERT INTO sub_order (id, sub_order_no, order_id, shop_id, shop_name, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                SUB_ID, SUB_NO, ORDER_ID, SHOP_A, "LOGFLOW_木集小铺", 199.00, status);
    }

    private void insertOrderItem() {
        jdbcTemplate.update("INSERT INTO order_item (id, sub_order_id, spu_id, sku_id, product_name, main_img, "
                        + "spec_json, price, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ITEM_ID, SUB_ID, 998_210_000_041L, 998_220_000_041L,
                "LOGFLOW_木椅", "/static/x.jpg", "{\"颜色\":\"原木色\"}", 199.00, 1);
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM order_item WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM shipment WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM sub_order WHERE id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
        traceRepository.deleteAll();
    }
}
