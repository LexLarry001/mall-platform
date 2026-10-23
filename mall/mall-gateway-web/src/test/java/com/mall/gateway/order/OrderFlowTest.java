package com.mall.gateway.order;

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结算下单 HTTP 链路测试（plan Task 3 验收②）。
 *
 * <p>真实安全过滤链 + 真实 MySQL（mall 库）+ 真实 Redis（购物车/防重锁/超时 ZSet）；
 * TokenStore / CaptchaService mock。fixture 以 ORDERFLOW_ 前缀与固定位 ID 命名，
 * 前后物理清理（含 Redis 键），保证可重复执行。</p>
 */
@SpringBootTest(
        classes = OrderFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + OrderFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class OrderFlowTest {

    static final String SECRET = "order-flow-test-secret-0123456789abcdef";
    private static final Long USER_A = 998_000_000_021L;
    private static final Long USER_B = 998_000_000_022L;

    private static final Long ADDR_A = 998_230_000_001L;
    private static final Long ADDR_B = 998_230_000_002L;

    private static final Long SHOP_A = 998_200_000_011L;
    private static final Long SHOP_B = 998_200_000_012L;
    private static final Long SPU_A = 998_210_000_011L;
    private static final Long SPU_B = 998_210_000_012L;
    private static final Long SKU_A1 = 998_220_000_011L;
    private static final Long SKU_A2 = 998_220_000_012L;
    private static final Long SKU_B1 = 998_220_000_013L;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        insertUser(USER_A, "ORDERFLOW_user_a");
        insertUser(USER_B, "ORDERFLOW_user_b");
        insertAddress(ADDR_A, USER_A, "张三");
        insertAddress(ADDR_B, USER_B, "李四");
        jdbcTemplate.update("INSERT INTO shop (id, merchant_id, name) VALUES (?, ?, 'ORDERFLOW_甲店')",
                SHOP_A, 998_100_000_011L);
        jdbcTemplate.update("INSERT INTO shop (id, merchant_id, name) VALUES (?, ?, 'ORDERFLOW_乙店')",
                SHOP_B, 998_100_000_012L);
        jdbcTemplate.update("INSERT INTO product (id, shop_id, category_id, name, main_img, price, status)"
                + " VALUES (?, ?, 1, 'ORDERFLOW_原木杯', '/img/a.jpg', 20.00, 2)", SPU_A, SHOP_A);
        jdbcTemplate.update("INSERT INTO product (id, shop_id, category_id, name, main_img, price, status)"
                + " VALUES (?, ?, 1, 'ORDERFLOW_橡木凳', '/img/b.jpg', 7.50, 2)", SPU_B, SHOP_B);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock, sku_img)"
                + " VALUES (?, ?, '{\"颜色\":\"原木色\"}', 20.00, 10, '/img/sku_a1.jpg')", SKU_A1, SPU_A);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock)"
                + " VALUES (?, ?, '{\"颜色\":\"胡桃色\"}', 5.00, 10)", SKU_A2, SPU_A);
        jdbcTemplate.update("INSERT INTO product_sku (id, spu_id, spec_json, price, stock)"
                + " VALUES (?, ?, '{}', 7.50, 10)", SKU_B1, SPU_B);
        tokenA = buyerToken(USER_A);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    /**
     * 全链路：跨店加购 → 结算（默认地址+试算）→ 下单（1 主 2 子，金额齐，库存扣减，
     * 地址快照，购物车清空，ZSet 写超时）→ 防重锁挡住重复提交。
     */
    @Test
    void settle_and_submit_cross_shop_chain() throws Exception {
        // 加购：甲店 A1x2 + A2x1，乙店 B1x2
        addToCart(SKU_A1, 2);
        addToCart(SKU_A2, 1);
        addToCart(SKU_B1, 2);

        // 结算：默认地址 + 3 条勾选 + 试算 40+5+15=60.00
        mockMvc.perform(get("/api/user/order/settle").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.defaultAddress.id").value(ADDR_A))
                .andExpect(jsonPath("$.data.defaultAddress.receiver").value("张三"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.totalAmount").value(60.0));

        // 下单 → 返回主订单号 M 开头
        MvcResult result = mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_A + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(startsWith("M")))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String orderNo = body.get("data").asText();

        // 主订单：1 行，状态 0，金额 60.00，地址快照，支付截止 ~30 分钟后
        var orderRow = jdbcTemplate.queryForMap(
                "SELECT * FROM `order` WHERE order_no = ?", orderNo);
        Long orderId = ((Number) orderRow.get("id")).longValue();
        assertThat(((Number) orderRow.get("user_id")).longValue()).isEqualTo(USER_A);
        assertThat((BigDecimal) orderRow.get("total_amount")).isEqualByComparingTo("60.00");
        assertThat((BigDecimal) orderRow.get("pay_amount")).isEqualByComparingTo("60.00");
        assertThat(((Number) orderRow.get("status")).intValue()).isZero();
        assertThat((String) orderRow.get("receiver")).isEqualTo("张三");
        assertThat((String) orderRow.get("full_address")).isEqualTo("浙江省杭州市西湖区ORDERFLOW_详细地址");

        // 子订单：2 行（一店一单），金额 45.00 + 15.00 = 60.00
        var subRows = jdbcTemplate.queryForList(
                "SELECT * FROM sub_order WHERE order_id = ? ORDER BY shop_id", orderId);
        assertThat(subRows).hasSize(2);
        assertThat((BigDecimal) subRows.get(0).get("amount")).isEqualByComparingTo("45.00");
        assertThat((BigDecimal) subRows.get(1).get("amount")).isEqualByComparingTo("15.00");
        assertThat(subRows.get(0).get("sub_order_no").toString()).startsWith("S");

        // 订单项：3 条快照
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_item oi JOIN sub_order s ON oi.sub_order_id = s.id"
                        + " WHERE s.order_id = ?", Integer.class, orderId);
        assertThat(itemCount).isEqualTo(3);

        // 库存扣减：A1 10-2=8，A2 10-1=9，B1 10-2=8
        assertThat(stockOf(SKU_A1)).isEqualTo(8);
        assertThat(stockOf(SKU_A2)).isEqualTo(9);
        assertThat(stockOf(SKU_B1)).isEqualTo(8);

        // 购物车已勾选清空
        Long cartSize = redis.opsForHash().size("mall:cart:" + USER_A);
        assertThat(cartSize).isZero();

        // ZSet 写超时锚点：member=主订单号
        Double score = redis.opsForZSet().score("mall:order:timeout", orderNo);
        assertThat(score).isNotNull();

        // 防重锁：5 秒内重复提交被拒
        mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_A + "}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("请勿重复提交"));
    }

    /** 超卖：任一 SKU 库存不足整单回滚——已扣库存回补（事务回滚），不落任何订单数据。 */
    @Test
    void submit_oversell_rolls_back_whole_order() throws Exception {
        addToCart(SKU_A1, 2);   // 够扣（10>=2）
        addToCart(SKU_A2, 99);  // 超卖（99>10）

        mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_A + "}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("库存不足")));

        // 整单回滚：无订单数据，A1 库存不变
        assertThat(orderCountOf(USER_A)).isZero();
        assertThat(stockOf(SKU_A1)).isEqualTo(10);
        assertThat(stockOf(SKU_A2)).isEqualTo(10);
    }

    /** 下单传他人地址：业务错误"地址不存在"，不泄露存在性。 */
    @Test
    void submit_address_of_other_user_rejected() throws Exception {
        addToCart(SKU_A1, 1);

        mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_B + "}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("地址不存在"));
        assertThat(orderCountOf(USER_A)).isZero();
    }

    /** 购物车无勾选项：下单业务错误"没有可结算的商品"；结算同样报错。 */
    @Test
    void submit_empty_cart_rejected() throws Exception {
        mockMvc.perform(get("/api/user/order/settle").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("没有可结算的商品"));

        mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_A + "}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("没有可结算的商品"));
    }

    /** 未登录 401；商家 token 访问买家订单接口 403。 */
    @Test
    void order_requires_buyer_role() throws Exception {
        mockMvc.perform(get("/api/user/order/settle"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String merchantToken = jwtTokenProvider.createToken(USER_B, AccountType.MERCHANT, null);
        when(tokenStore.get(merchantToken)).thenReturn(new LoginUser(USER_B, AccountType.MERCHANT, null, null));
        when(tokenStore.isCurrent(merchantToken, AccountType.MERCHANT, USER_B)).thenReturn(true);

        mockMvc.perform(get("/api/user/order/settle").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 订单中心链路：下单 → 列表（status 过滤）→ 详情（主+子+项聚合）→
     * 取消（级联子订单 + 回补库存 + ZSet 移除）→ 重复取消被拒。
     */
    @Test
    void list_detail_cancel_chain() throws Exception {
        String orderNo = submitOrderOfA();
        String orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE order_no = ?", String.class, orderNo);

        // 列表：全部 1 条；status=0 过滤命中；status=3 过滤为空
        mockMvc.perform(get("/api/user/order/list").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNo").value(orderNo))
                .andExpect(jsonPath("$.data[0].status").value(0))
                .andExpect(jsonPath("$.data[0].totalAmount").value(60.0))
                .andExpect(jsonPath("$.data[0].items.length()").value(3));
        mockMvc.perform(get("/api/user/order/list?status=0").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/user/order/list?status=3").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 详情：地址快照 + 2 子订单 + 订单项归属
        mockMvc.perform(get("/api/user/order/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.receiver").value("张三"))
                .andExpect(jsonPath("$.data.fullAddress")
                        .value("浙江省杭州市西湖区ORDERFLOW_详细地址"))
                .andExpect(jsonPath("$.data.subOrders.length()").value(2))
                .andExpect(jsonPath("$.data.subOrders[0].subOrderNo").value(startsWith("S")))
                .andExpect(jsonPath("$.data.subOrders[0].items.length()").value(2))
                .andExpect(jsonPath("$.data.subOrders[1].items.length()").value(1));

        // 取消：主 0→3、子 0→4、库存回补（A1 8→10、A2 9→10、B1 8→10）、ZSet 移除
        mockMvc.perform(post("/api/user/order/{orderNo}/cancel", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0));

        Integer mainStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        assertThat(mainStatus).isEqualTo(3);
        Integer cancelledSubs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sub_order WHERE order_id = ? AND status = 4",
                Integer.class, orderId);
        assertThat(cancelledSubs).isEqualTo(2);
        assertThat(stockOf(SKU_A1)).isEqualTo(10);
        assertThat(stockOf(SKU_A2)).isEqualTo(10);
        assertThat(stockOf(SKU_B1)).isEqualTo(10);
        assertThat(redis.opsForZSet().score("mall:order:timeout", orderNo)).isNull();

        // 重复取消：业务错误"当前状态不可取消"
        mockMvc.perform(post("/api/user/order/{orderNo}/cancel", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("当前状态不可取消"));
    }

    /** 他人订单：详情/取消一律"订单不存在"，不泄露存在性。 */
    @Test
    void detail_and_cancel_of_other_user_rejected() throws Exception {
        String orderNo = submitOrderOfA();
        String tokenB = buyerToken(USER_B);

        mockMvc.perform(get("/api/user/order/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("订单不存在"));

        mockMvc.perform(post("/api/user/order/{orderNo}/cancel", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("订单不存在"));

        // 本人仍可正常查看（未被他人操作影响）
        mockMvc.perform(get("/api/user/order/{orderNo}", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(0));
    }

    // ------------------------------------------------------------------ helpers

    /** USER_A 加购 A1x2 + A2x1 + B1x2 并下单，返回主订单号。 */
    private String submitOrderOfA() throws Exception {
        addToCart(SKU_A1, 2);
        addToCart(SKU_A2, 1);
        addToCart(SKU_B1, 2);
        MvcResult result = mockMvc.perform(post("/api/user/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + ADDR_A + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").asText();
    }

    private void addToCart(Long skuId, int quantity) throws Exception {
        mockMvc.perform(post("/api/user/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + skuId + ",\"quantity\":" + quantity + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private int stockOf(Long skuId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM product_sku WHERE id = ?", Integer.class, skuId);
        return stock == null ? -1 : stock;
    }

    private int orderCountOf(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
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

    private void insertAddress(Long id, Long userId, String receiver) {
        jdbcTemplate.update("INSERT INTO user_address"
                        + " (id, user_id, receiver, phone, province, city, district, detail, is_default)"
                        + " VALUES (?, ?, ?, '13800000000', '浙江省', '杭州市', '西湖区', 'ORDERFLOW_详细地址', 1)",
                id, userId, receiver);
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
        jdbcTemplate.update("DELETE oi FROM order_item oi"
                + " JOIN sub_order s ON oi.sub_order_id = s.id"
                + " JOIN `order` o ON s.order_id = o.id WHERE o.user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE s FROM sub_order s"
                + " JOIN `order` o ON s.order_id = o.id WHERE o.user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE FROM `order` WHERE user_id IN (?, ?)", USER_A, USER_B);
        jdbcTemplate.update("DELETE FROM user_address WHERE id IN (?, ?)", ADDR_A, ADDR_B);
        jdbcTemplate.update("DELETE FROM product_sku WHERE id IN (?, ?, ?)", SKU_A1, SKU_A2, SKU_B1);
        jdbcTemplate.update("DELETE FROM product WHERE id IN (?, ?)", SPU_A, SPU_B);
        jdbcTemplate.update("DELETE FROM shop WHERE id IN (?, ?)", SHOP_A, SHOP_B);
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'ORDERFLOW\\_%'");
    }
}
