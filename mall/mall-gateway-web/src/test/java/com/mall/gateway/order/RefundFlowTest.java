package com.mall.gateway.order;

import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
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
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6 退款售后 HTTP 链路测试（Task 2 申请/查询 + Task 3 审核/退款）。
 *
 * <p>真实安全过滤链 + 真实 MySQL/Redis；AlipayClient mock（不依赖沙箱网络）。
 * fixture 以 REFFLOW_ 前缀与固定位 ID 命名，前后物理清理，保证可重复执行。</p>
 */
@SpringBootTest(
        classes = RefundFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + RefundFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "mall.alipay.notify-url=http://gateway.test/api/pay/notify",
                "mall.alipay.return-url=http://localhost:5173/pay/result",
                "mall.logistics.provider=manual"
        })
@AutoConfigureMockMvc
class RefundFlowTest {

    static final String SECRET = "refund-flow-test-secret-0123456789abcdef";
    private static final Long BUYER_A = 997_000_000_051L;
    private static final Long BUYER_B = 997_000_000_052L;
    private static final Long SHOP_A = 997_200_000_051L;
    private static final Long SHOP_B = 997_200_000_052L;

    private static final Long ORDER_ID = 997_300_000_051L;
    private static final Long SUB_ID = 997_310_000_051L;
    private static final Long ITEM_1 = 997_320_000_051L;
    private static final Long ITEM_2 = 997_320_000_052L;
    private static final Long SKU_1 = 997_210_000_051L;
    private static final Long SKU_2 = 997_210_000_052L;

    private static final String ORDER_NO = "REF_M0001";
    private static final String SUB_NO = "REF_S0001";

    @SpringBootApplication(scanBasePackages = {
            "com.mall.merchant", "com.mall.admin", "com.mall.product", "com.mall.order",
            "com.mall.gateway.auth", "com.mall.gateway.config", "com.mall.gateway.order",
            "com.mall.gateway.logistics", "com.mall.gateway.refund", "com.mall.ai", "com.mall.security"})
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
    /** 沙箱密钥不进测试：退款客户端一律 mock。 */
    @MockBean
    private AlipayClient alipayClient;

    private String buyerToken;
    private String otherBuyerToken;
    private String merchantToken;
    private String otherMerchantToken;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertSku(SKU_1, 10);
        insertSku(SKU_2, 10);
        insertOrder(1);
        insertSubOrder(1);
        insertItem(ITEM_1, SKU_1, "REF_木椅", "100.00", 1);
        insertItem(ITEM_2, SKU_2, "REF_木桌", "50.00", 2);

        buyerToken = token(BUYER_A, AccountType.USER, null);
        otherBuyerToken = token(BUYER_B, AccountType.USER, null);
        merchantToken = token(BUYER_A, AccountType.MERCHANT, SHOP_A);
        otherMerchantToken = token(BUYER_B, AccountType.MERCHANT, SHOP_B);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // ------------------------------------------------------------- Task 2 申请

    /** 订单详情携带 itemId；买家可据此发起退款申请。 */
    @Test
    void order_detail_exposes_item_id_for_refund() throws Exception {
        mockMvc.perform(get("/api/user/order/{orderNo}", ORDER_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.subOrders[0].items[0].itemId").value(ITEM_1))
                .andExpect(jsonPath("$.data.subOrders[0].items[1].itemId").value(ITEM_2));
    }

    /** 申请退款：金额取快照 → 子订单 5 退款中 → 列表可查；越权被拒 403。 */
    @Test
    void buyer_apply_refund_full_chain() throws Exception {
        // 未登录 / 商家 token 访问买家接口
        mockMvc.perform(post("/api/user/refund").contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(ITEM_1)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/user/refund").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson(ITEM_1)))
                .andExpect(status().isForbidden());

        // 越权买家
        mockMvc.perform(post("/api/user/refund").header(HttpHeaders.AUTHORIZATION, bearer(otherBuyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson(ITEM_1)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("订单不存在")));

        // 本人申请成功
        String response = mockMvc.perform(post("/api/user/refund")
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson(ITEM_1)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(startsWith("RF")))
                .andReturn().getResponse().getContentAsString();
        String refundNo = com.jayway.jsonpath.JsonPath.read(response, "$.data");

        assertThat(subStatus()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refund_amount FROM refund_order WHERE refund_no = ?", String.class, refundNo))
                .isEqualTo("100.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sub_status_before FROM refund_order WHERE refund_no = ?", Integer.class, refundNo))
                .isEqualTo(1);

        // 列表：买家本人可查
        mockMvc.perform(get("/api/user/refund/order/{orderNo}", ORDER_NO)
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].refundNo").value(refundNo))
                .andExpect(jsonPath("$.data[0].status").value(0))
                .andExpect(jsonPath("$.data[0].evidenceImages[0]").value("/static/evidence.jpg"));

        // 详情：他人不可见
        mockMvc.perform(get("/api/user/refund/{refundNo}", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherBuyerToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("退款单不存在")));

        // 子订单退款中，再次申请被拒
        mockMvc.perform(post("/api/user/refund").header(HttpHeaders.AUTHORIZATION, bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson(ITEM_2)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("当前状态不可申请退款")));
    }

    /** 同一订单项已有待审核退款单时重复申请被拒。 */
    @Test
    void buyer_apply_rejects_duplicate_item() throws Exception {
        jdbcTemplate.update("INSERT INTO refund_order (id, refund_no, order_id, order_no, sub_order_id, "
                        + "sub_order_no, order_item_id, spu_id, sku_id, product_name, shop_id, user_id, quantity, "
                        + "refund_amount, reason, status, sub_status_before) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 1)",
                997_400_000_051L, "RF_DUP_0001", ORDER_ID, ORDER_NO, SUB_ID, SUB_NO,
                ITEM_1, 997_220_000_051L, SKU_1, "REF_木椅", SHOP_A, BUYER_A, 1, "100.00", "不想要了");

        mockMvc.perform(post("/api/user/refund").header(HttpHeaders.AUTHORIZATION, bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson(ITEM_1)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("该商品已申请退款")));
    }

    // ------------------------------------------------------------- Task 3 审核

    /** 驳回：记录备注 → 退款单 3 → 子订单恢复 1；越权商家不可操作。 */
    @Test
    void merchant_reject_restores_sub_order_and_records_remark() throws Exception {
        String refundNo = apply(ITEM_1);

        mockMvc.perform(post("/api/merchant/refund/{refundNo}/reject", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherMerchantToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"非本店\"}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("退款单不存在")));

        mockMvc.perform(post("/api/merchant/refund/{refundNo}/reject", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"凭证不足\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(refundStatus(refundNo)).isEqualTo(3);
        assertThat(refundField(refundNo, "audit_remark")).isEqualTo("凭证不足");
        assertThat(subStatus()).isEqualTo(1);
        assertThat(orderStatus()).isEqualTo(1);

        // 已驳回不可再审
        mockMvc.perform(post("/api/merchant/refund/{refundNo}/approve", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("当前状态不可审核")));
    }

    /** 同意（部分退）→ 库存回补 + 子订单 7；再退剩余项 → 子订单 6 + 主订单 4 已退款。 */
    @Test
    void approve_success_partial_then_full_refund_updates_stock_and_order() throws Exception {
        when(alipayClient.execute(any(AlipayTradeRefundRequest.class)))
                .thenReturn(refundResponse(true, "ALI_TRADE_1", null, null));

        String first = apply(ITEM_1);
        mockMvc.perform(post("/api/merchant/refund/{refundNo}/approve", first)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"同意退款\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(refundStatus(first)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT alipay_trade_no FROM refund_order WHERE refund_no = ?", String.class, first))
                .isEqualTo("ALI_TRADE_1");
        assertThat(skuStock(SKU_1)).isEqualTo(11);
        assertThat(subStatus()).isEqualTo(7);
        assertThat(orderStatus()).isEqualTo(1);

        // 部分退款后剩余订单项仍可申请
        String second = apply(ITEM_2);
        mockMvc.perform(post("/api/merchant/refund/{refundNo}/approve", second)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(skuStock(SKU_2)).isEqualTo(12);
        assertThat(subStatus()).isEqualTo(6);
        assertThat(orderStatus()).isEqualTo(4);
    }

    /** 已发货订单退款不回补库存（货已在买家手上）。 */
    @Test
    void approve_does_not_restore_stock_after_shipped() throws Exception {
        jdbcTemplate.update("UPDATE sub_order SET status = 2 WHERE id = ?", SUB_ID);
        insertShipment();
        when(alipayClient.execute(any(AlipayTradeRefundRequest.class)))
                .thenReturn(refundResponse(true, "ALI_TRADE_2", null, null));

        String refundNo = apply(ITEM_1);
        mockMvc.perform(post("/api/merchant/refund/{refundNo}/approve", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(refundStatus(refundNo)).isEqualTo(2);
        assertThat(skuStock(SKU_1)).isEqualTo(10);
        assertThat(subStatus()).isEqualTo(7);
    }

    /** 沙箱失败 → 4 退款失败 + 错误码落库 + 子订单恢复；重试成功 → 2。 */
    @Test
    void approve_failure_then_retry_succeeds() throws Exception {
        when(alipayClient.execute(any(AlipayTradeRefundRequest.class)))
                .thenReturn(refundResponse(false, null, "ACQ.SYSTEM_ERROR", "系统繁忙"),
                        refundResponse(true, "ALI_TRADE_3", null, null));

        String refundNo = apply(ITEM_1);

        mockMvc.perform(post("/api/merchant/refund/{refundNo}/approve", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("退款失败，可稍后重试")));

        assertThat(refundStatus(refundNo)).isEqualTo(4);
        assertThat(refundField(refundNo, "fail_code")).isEqualTo("ACQ.SYSTEM_ERROR");
        assertThat(subStatus()).isEqualTo(1);

        mockMvc.perform(post("/api/merchant/refund/{refundNo}/retry", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(refundStatus(refundNo)).isEqualTo(2);
        assertThat(skuStock(SKU_1)).isEqualTo(11);
        assertThat(subStatus()).isEqualTo(7);

        // 已成功不可再重试
        mockMvc.perform(post("/api/merchant/refund/{refundNo}/retry", refundNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("当前状态不可重试")));
    }

    /** 商家列表仅返回本店退款单，支持状态过滤。 */
    @Test
    void merchant_refund_list_is_scoped_to_shop() throws Exception {
        String refundNo = apply(ITEM_1);

        mockMvc.perform(get("/api/merchant/refund/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].refundNo").value(refundNo));

        mockMvc.perform(get("/api/merchant/refund/list").param("status", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/merchant/refund/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherMerchantToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------ helpers

    private String token(Long accountId, AccountType type, Long shopId) {
        String token = jwtTokenProvider.createToken(accountId, type, shopId);
        when(tokenStore.get(token)).thenReturn(new LoginUser(accountId, type, shopId, null));
        when(tokenStore.isCurrent(token, type, accountId)).thenReturn(true);
        return token;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String applyJson(Long itemId) {
        return "{\"orderNo\":\"" + ORDER_NO + "\",\"subOrderNo\":\"" + SUB_NO
                + "\",\"orderItemId\":" + itemId
                + ",\"reason\":\"商品质量问题\",\"evidenceImages\":[\"/static/evidence.jpg\"]}";
    }

    private Integer subStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM sub_order WHERE id = ?", Integer.class, SUB_ID);
    }

    private Integer orderStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, ORDER_ID);
    }

    private Integer skuStock(Long skuId) {
        return jdbcTemplate.queryForObject("SELECT stock FROM product_sku WHERE id = ?", Integer.class, skuId);
    }

    private void insertSku(Long skuId, int stock) {
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock) VALUES (?, ?, ?, ?, ?)",
                skuId, 997_220_000_051L, "{\"颜色\":\"原木色\"}", 100.00, stock);
    }

    private void insertOrder(int status) {
        jdbcTemplate.update("INSERT INTO `order` (id, order_no, user_id, total_amount, pay_amount, status, "
                        + "receiver, phone, full_address, expire_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 30 MINUTE))",
                ORDER_ID, ORDER_NO, BUYER_A, 200.00, 200.00, status,
                "REF_收货人", "13800000000", "上海市浦东新区世纪大道 1 号");
    }

    private void insertSubOrder(int status) {
        jdbcTemplate.update("INSERT INTO sub_order (id, sub_order_no, order_id, shop_id, shop_name, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                SUB_ID, SUB_NO, ORDER_ID, SHOP_A, "REF_木集小铺", 200.00, status);
    }

    private void insertItem(Long itemId, Long skuId, String name, String price, int quantity) {
        jdbcTemplate.update("INSERT INTO order_item (id, sub_order_id, spu_id, sku_id, product_name, main_img, "
                        + "spec_json, price, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                itemId, SUB_ID, 997_220_000_051L, skuId, name, "/static/x.jpg",
                "{\"颜色\":\"原木色\"}", new java.math.BigDecimal(price), quantity);
    }

    private String apply(Long itemId) throws Exception {
        String response = mockMvc.perform(post("/api/user/refund")
                        .header(HttpHeaders.AUTHORIZATION, bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson(itemId)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.data");
    }

    private Integer refundStatus(String refundNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM refund_order WHERE refund_no = ?", Integer.class, refundNo);
    }

    private String refundField(String refundNo, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM refund_order WHERE refund_no = ?", String.class, refundNo);
    }

    private static AlipayTradeRefundResponse refundResponse(boolean success, String tradeNo,
                                                            String subCode, String subMsg) {
        AlipayTradeRefundResponse response = new AlipayTradeRefundResponse();
        response.setCode(success ? "10000" : "40004");
        response.setMsg(success ? "Success" : "Business Failed");
        response.setSubCode(subCode);
        response.setSubMsg(subMsg);
        response.setTradeNo(tradeNo);
        return response;
    }

    private void insertShipment() {
        jdbcTemplate.update("INSERT INTO shipment (id, shipment_no, sub_order_id, sub_order_no, provider, "
                        + "carrier_code, carrier_name, tracking_no, current_status, trace_count) "
                        + "VALUES (?, ?, ?, ?, 'manual', 'YTO', '圆通', 'YTO_REF_1', 'SHIPPED', 1)",
                997_330_000_051L, "REF_SH0001", SUB_ID, SUB_NO);
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM refund_order WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM order_item WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM shipment WHERE sub_order_id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM sub_order WHERE id = ?", SUB_ID);
        jdbcTemplate.update("DELETE FROM `order` WHERE id = ?", ORDER_ID);
        jdbcTemplate.update("DELETE FROM product_sku WHERE id IN (?, ?)", SKU_1, SKU_2);
    }
}
