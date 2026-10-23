package com.mall.gateway.order;

import com.jayway.jsonpath.JsonPath;
import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.order.logistics.AmapClient;
import com.mall.order.logistics.LogisticsStatus;
import com.mall.order.logistics.LogisticsTraceDoc;
import com.mall.order.logistics.LogisticsTraceRepository;
import com.mall.order.logistics.RoutePlan;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M7 Task 3：高德发货链路集成测试（真实 MySQL + MongoDB，mock {@link AmapClient}）。
 *
 * <p>覆盖：发货落库地址/坐标、ROUTE + SHIPPED 双节点、买家接口可见、推进三态插值、
 * 编码失败降级 manual、越权回归。fixture 以 AMPFLOW_ 前缀命名，前后物理清理。</p>
 */
@SpringBootTest(
        classes = AmapShippingFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + AmapShippingFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "mall.logistics.provider=amap"
        })
@AutoConfigureMockMvc
class AmapShippingFlowTest {

    static final String SECRET = "amap-shipping-flow-secret-0123456789abcdef";
    private static final Long BUYER_A = 997_000_000_041L;
    private static final Long BUYER_B = 997_000_000_042L;
    private static final Long SHOP_A = 997_200_000_041L;
    private static final Long SHOP_B = 997_200_000_042L;

    private static final Long ORDER_ID = 997_300_000_041L;
    private static final Long SUB_ID = 997_310_000_041L;
    private static final Long ITEM_ID = 997_320_000_041L;

    private static final String ORDER_NO = "AMPFLOW_M0001";
    private static final String SUB_NO = "AMPFLOW_S0001";
    private static final String FROM_ADDRESS = "上海市浦东新区世纪大道 100 号";
    private static final String TO_ADDRESS = "上海市徐汇区漕溪北路 200 号";

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
    private AmapClient amapClient;
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
        insertSubOrder();
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

    /** 高德规划成功：地址/坐标落库、ROUTE + SHIPPED、买家可见；越权仍被拒。 */
    @Test
    void ship_with_route_persists_addresses_and_coordinates() throws Exception {
        when(amapClient.driving(FROM_ADDRESS, TO_ADDRESS)).thenReturn(sampleRoute());

        // 越权发货被拒
        mockMvc.perform(post("/api/merchant/ship/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherMerchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipJson(FROM_ADDRESS)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));

        String shipmentNo = postForData("/api/merchant/ship/" + SUB_NO, merchantToken, shipJson(FROM_ADDRESS));
        assertThat(shipmentNo).startsWith("SH");

        Map<String, Object> row = shipmentRow();
        assertThat(row.get("provider")).isEqualTo("amap");
        assertThat(row.get("from_address")).isEqualTo(FROM_ADDRESS);
        assertThat(row.get("to_address")).isEqualTo(TO_ADDRESS);
        assertThat(new BigDecimal(row.get("from_lng").toString())).isEqualByComparingTo("121.000000");
        assertThat(new BigDecimal(row.get("from_lat").toString())).isEqualByComparingTo("31.000000");
        assertThat(new BigDecimal(row.get("to_lng").toString())).isEqualByComparingTo("121.100000");
        assertThat(new BigDecimal(row.get("to_lat").toString())).isEqualByComparingTo("31.100000");
        assertThat(((Number) row.get("trace_count")).intValue()).isEqualTo(2);

        List<LogisticsTraceDoc> docs = traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo);
        assertThat(docs).hasSize(2);
        LogisticsTraceDoc routeDoc = docs.stream()
                .filter(d -> LogisticsTraceDoc.NODE_ROUTE.equals(d.getNodeType()))
                .findFirst().orElseThrow();
        assertThat(routeDoc.getPolyline()).hasSize(3);
        assertThat(routeDoc.getAddress()).isEqualTo(FROM_ADDRESS);
        LogisticsTraceDoc shipped = findStatus(docs, LogisticsStatus.SHIPPED);
        assertThat(shipped.getLng()).isEqualByComparingTo("121.000000");
        assertThat(shipped.getLat()).isEqualByComparingTo("31.000000");

        // 买家发货单接口：地址与 provider 可见
        mockMvc.perform(get("/api/user/logistics/shipment/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.provider").value("amap"))
                .andExpect(jsonPath("$.data.fromAddress").value(FROM_ADDRESS))
                .andExpect(jsonPath("$.data.toAddress").value(TO_ADDRESS));
        mockMvc.perform(get("/api/user/logistics/trace/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));

        // 越权查询被拒
        mockMvc.perform(get("/api/user/logistics/trace/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherBuyerToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));
    }

    /** 推进三态：按 50% / 85% / 100% 插值写坐标；签收后不可再推进。 */
    @Test
    void push_status_interpolates_coordinates_and_stops_after_delivered() throws Exception {
        when(amapClient.driving(FROM_ADDRESS, TO_ADDRESS)).thenReturn(sampleRoute());
        String shipmentNo = postForData("/api/merchant/ship/" + SUB_NO, merchantToken, shipJson(FROM_ADDRESS));

        push(shipmentNo, "包裹已到达上海分拣中心", "IN_TRANSIT");
        push(shipmentNo, "快递员正在派送", "OUT_FOR_DELIVERY");
        push(shipmentNo, "已签收", "DELIVERED");

        List<LogisticsTraceDoc> docs = traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo);
        assertThat(docs).hasSize(5);
        assertStatusCoordinate(docs, LogisticsStatus.IN_TRANSIT, "121.050000", "31.050000");
        assertStatusCoordinate(docs, LogisticsStatus.OUT_FOR_DELIVERY, "121.085000", "31.085000");
        assertStatusCoordinate(docs, LogisticsStatus.DELIVERED, "121.100000", "31.100000");

        mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desc\":\"再推\"}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("物流已签收")));
    }

    /** 高德不可用（编码失败）：发货仍成功，降级 manual，无 ROUTE 节点。 */
    @Test
    void shipping_degrades_to_manual_when_route_unavailable() throws Exception {
        when(amapClient.driving(anyString(), anyString()))
                .thenReturn(RoutePlan.unavailable(FROM_ADDRESS, TO_ADDRESS));

        String shipmentNo = postForData("/api/merchant/ship/" + SUB_NO, merchantToken, shipJson(FROM_ADDRESS));
        assertThat(shipmentNo).startsWith("SH");

        Map<String, Object> row = shipmentRow();
        assertThat(row.get("provider")).isEqualTo("manual");
        assertThat(row.get("from_lng")).isNull();
        assertThat(row.get("to_lng")).isNull();
        assertThat(((Number) row.get("trace_count")).intValue()).isEqualTo(1);

        List<LogisticsTraceDoc> docs = traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo);
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getNodeType()).isEqualTo(LogisticsTraceDoc.NODE_STATUS);
        assertThat(docs.get(0).getStatus()).isEqualTo(LogisticsStatus.SHIPPED);
        assertThat(docs.get(0).getPolyline()).isNull();

        // 降级后推进只写文字节点
        push(shipmentNo, "包裹到达分拣中心", "IN_TRANSIT");
        LogisticsTraceDoc inTransit = findStatus(
                traceRepository.findByShipmentNoOrderByTsDesc(shipmentNo), LogisticsStatus.IN_TRANSIT);
        assertThat(inTransit.getLng()).isNull();
    }

    /** amap 模式发货地址必填。 */
    @Test
    void shipping_requires_from_address_in_amap_mode() throws Exception {
        mockMvc.perform(post("/api/merchant/ship/{subOrderNo}", SUB_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipJson(null)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("请填写发货地址")));
    }

    // ------------------------------------------------------------------ helpers

    private static RoutePlan sampleRoute() {
        return new RoutePlan(121.0, 31.0, 121.1, 31.1,
                List.of(new double[]{121.0, 31.0},
                        new double[]{121.05, 31.05},
                        new double[]{121.1, 31.1}),
                10000, 1200, FROM_ADDRESS, TO_ADDRESS, true);
    }

    private void push(String shipmentNo, String desc, String expected) throws Exception {
        mockMvc.perform(post("/api/merchant/ship/progress/{shipmentNo}", shipmentNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desc\":\"" + desc + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(expected));
    }

    private static LogisticsTraceDoc findStatus(List<LogisticsTraceDoc> docs, String status) {
        return docs.stream()
                .filter(d -> LogisticsTraceDoc.NODE_STATUS.equals(d.getNodeType()))
                .filter(d -> status.equals(d.getStatus()))
                .findFirst().orElseThrow();
    }

    private static void assertStatusCoordinate(List<LogisticsTraceDoc> docs, String status,
                                               String lng, String lat) {
        LogisticsTraceDoc doc = findStatus(docs, status);
        assertThat(doc.getLng()).isEqualByComparingTo(lng);
        assertThat(doc.getLat()).isEqualByComparingTo(lat);
    }

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
        return JsonPath.read(response, "$.data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String shipJson(String fromAddress) {
        String base = "{\"carrierCode\":\"YTO\",\"carrierName\":\"圆通\",\"trackingNo\":\"YTO123456789\"";
        return fromAddress == null ? base + "}" : base + ",\"fromAddress\":\"" + fromAddress + "\"}";
    }

    private Map<String, Object> shipmentRow() {
        return jdbcTemplate.queryForMap("SELECT provider, from_address, to_address, from_lng, from_lat, "
                + "to_lng, to_lat, trace_count FROM shipment WHERE sub_order_id = ?", SUB_ID);
    }

    private void insertOrder() {
        jdbcTemplate.update("INSERT INTO `order` (id, order_no, user_id, total_amount, pay_amount, status, "
                        + "receiver, phone, full_address, expire_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 30 MINUTE))",
                ORDER_ID, ORDER_NO, BUYER_A, 199.00, 199.00, 1,
                "AMPFLOW_收货人", "13800000001", TO_ADDRESS);
    }

    private void insertSubOrder() {
        jdbcTemplate.update("INSERT INTO sub_order (id, sub_order_no, order_id, shop_id, shop_name, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                SUB_ID, SUB_NO, ORDER_ID, SHOP_A, "AMPFLOW_木集小铺", 199.00, 1);
    }

    private void insertOrderItem() {
        jdbcTemplate.update("INSERT INTO order_item (id, sub_order_id, spu_id, sku_id, product_name, main_img, "
                        + "spec_json, price, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ITEM_ID, SUB_ID, 997_210_000_041L, 997_220_000_041L,
                "AMPFLOW_木椅", "/static/x.jpg", "{\"颜色\":\"原木色\"}", 199.00, 1);
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM order_item WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM shipment WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM sub_order WHERE id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
        traceRepository.deleteAll();
    }
}
