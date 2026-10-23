package com.mall.gateway.merchant;

import com.jayway.jsonpath.JsonPath;
import com.mall.common.exception.GlobalExceptionHandler;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.entity.ShopEntity;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家入驻 → 平台审核 → 开店 → 登录绑定 shopId 的全链路测试（plan Task 3 Step 1 的①-⑤）。
 *
 * <p>真实安全过滤链 + 真实 MySQL / MongoDB（mall 库，与 ProductModuleSmokeTest 同口径）；
 * TokenStore / CaptchaService mock，因此运行不依赖 Redis。测试数据以固定用户名
 * {@value #FLOW_USERNAME} 写入，前后均以物理删除清理（含 Mongo 留痕），保证可重复执行。</p>
 */
@SpringBootTest(
        classes = MerchantAuditFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + MerchantAuditFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class MerchantAuditFlowTest {

    static final String SECRET = "merchant-audit-flow-test-secret-0123456789abcdef";
    private static final String FLOW_USERNAME = "m2flow_seller";
    private static final String APPLY_BODY = """
            {"subjectName":"流转测试商贸有限公司","licenseNo":"91330100FLOW0001",
             "licenseImg":"/static/202610/license.png","shopName":"流转测试店",
             "shopLogo":"/static/202610/logo.png","shopIntro":"测试简介"}
            """;

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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MongoTemplate mongoTemplate;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private CaptchaService captchaService;

    @BeforeEach
    @AfterEach
    void cleanup() {
        MerchantEntity merchant = merchantMapper.selectByUsername(FLOW_USERNAME);
        if (merchant != null) {
            jdbcTemplate.update("DELETE FROM shop WHERE merchant_id = ?", merchant.getId());
            jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", merchant.getId());
            mongoTemplate.remove(Query.query(Criteria.where("targetId").is(merchant.getId())),
                    "merchant_audit_log");
        }
    }

    /** 全链路：注册 → 申请（重复拒绝）→ 驳回 → 再申请 → 通过开店 → 重登绑 shopId → 冻结后禁登。 */
    @Test
    void merchant_onboarding_full_flow() throws Exception {
        // 1. 注册商家
        mockMvc.perform(post("/api/merchant/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + FLOW_USERNAME + "\",\"password\":\"secret123\",\"contactPhone\":\"13800000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        Long merchantId = merchantMapper.selectByUsername(FLOW_USERNAME).getId();

        // 2. 登录（未开店 → shopId 为空），拿到商家 token
        String merchantToken = loginAndGetToken(FLOW_USERNAME, null);

        // 3. 提交入驻申请 → 成功
        apply(merchantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 4. 待审核中重复提交 → 业务错误（①）
        apply(merchantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("重复提交")));

        // 5. 申请状态可查
        mockMvc.perform(get("/api/merchant/apply/status").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditStatus").value(0))
                .andExpect(jsonPath("$.data.subjectName").value("流转测试商贸有限公司"));

        // 6. 平台审核列表：仅含"已提交申请"的商家（本用例中每条记录 subjectName 均非空）
        String adminToken = givenAdminToken(Set.of(
                "merchant:audit:list", "merchant:audit:handle", "merchant:manage:status"));
        String listResponse = mockMvc.perform(get("/api/admin/merchant/audit/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> records = JsonPath.read(listResponse, "$.data.records");
        Integer total = JsonPath.read(listResponse, "$.data.total");
        assertThat(total).isGreaterThanOrEqualTo(records.size());
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(record -> assertThat(record.get("subjectName")).isNotNull());
        assertThat(records).anySatisfy(record -> assertThat(record.get("username")).isEqualTo(FLOW_USERNAME));

        // 7. 驳回 → 状态 2 + 原因
        mockMvc.perform(post("/api/admin/merchant/audit/{id}/reject", merchantId)
                        .param("remark", "资料不全")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/merchant/apply/status").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditStatus").value(2))
                .andExpect(jsonPath("$.data.auditRemark").value("资料不全"));

        // 8. 驳回后可重填再提交（②）
        apply(merchantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 9. 审核通过 → 自动建店
        mockMvc.perform(post("/api/admin/merchant/audit/{id}/pass", merchantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        ShopEntity shop = shopMapper.selectByMerchantId(merchantId);
        assertThat(shop).isNotNull();
        assertThat(shop.getName()).isEqualTo("流转测试店");

        // 10. 重复通过 → 业务错误且店铺唯一（③ RF7）
        mockMvc.perform(post("/api/admin/merchant/audit/{id}/pass", merchantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        Integer shopCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop WHERE merchant_id = ?", Integer.class, merchantId);
        assertThat(shopCount).isEqualTo(1);

        // Mongo 审核留痕齐全：首次 SUBMIT → REJECT → 重提 SUBMIT → PASS（重复通过未留痕）
        List<String> actions = mongoTemplate
                .find(Query.query(Criteria.where("targetId").is(merchantId)), Map.class, "merchant_audit_log")
                .stream().map(doc -> (String) doc.get("action")).toList();
        assertThat(actions).hasSize(4);
        assertThat(actions).contains("SUBMIT", "REJECT", "PASS");

        // 11. 重新登录 → token 绑定 shopId（④）
        String reboundToken = loginAndGetToken(FLOW_USERNAME, shop.getId());

        // 12. 冻结商家 → 登录被拒"账号已被冻结"（⑤）
        mockMvc.perform(post("/api/admin/merchant/{id}/status", merchantId)
                        .param("status", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/merchant/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(FLOW_USERNAME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("账号已被冻结"));
        assertThat(reboundToken).isNotBlank();
    }

    @Test
    void anonymous_apply_should_return_401() throws Exception {
        mockMvc.perform(post("/api/merchant/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLY_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void buyer_token_cannot_call_merchant_apply() throws Exception {
        String buyerToken = jwtTokenProvider.createToken(9L, AccountType.USER, null);
        when(tokenStore.get(buyerToken)).thenReturn(new LoginUser(9L, AccountType.USER, null, null));
        when(tokenStore.isCurrent(buyerToken, AccountType.USER, 9L)).thenReturn(true);

        apply(buyerToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void admin_without_audit_permission_should_return_403() throws Exception {
        String lowAdminToken = givenAdminToken(Set.of("system:user:list"));

        mockMvc.perform(get("/api/admin/merchant/audit/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lowAdminToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private org.springframework.test.web.servlet.ResultActions apply(String token) throws Exception {
        return mockMvc.perform(post("/api/merchant/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(APPLY_BODY)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    /** 走真实登录接口拿 token，并按预期 shopId 断言响应，随后把登录态 stub 进 TokenStore。 */
    private String loginAndGetToken(String username, Long expectedShopId) throws Exception {
        String response = mockMvc.perform(post("/api/merchant/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(expectedShopId == null
                        ? jsonPath("$.data.shopId").value(org.hamcrest.Matchers.nullValue())
                        : jsonPath("$.data.shopId").value(expectedShopId))
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(response, "$.data.accessToken");
        Long accountId = merchantMapper.selectByUsername(username).getId();
        when(tokenStore.get(token))
                .thenReturn(new LoginUser(accountId, AccountType.MERCHANT, expectedShopId, null));
        when(tokenStore.isCurrent(token, AccountType.MERCHANT, accountId)).thenReturn(true);
        return token;
    }

    private String givenAdminToken(Set<String> permissions) {
        String token = jwtTokenProvider.createToken(1L, AccountType.ADMIN, null);
        when(tokenStore.get(token)).thenReturn(new LoginUser(1L, AccountType.ADMIN, null, permissions));
        when(tokenStore.isCurrent(token, AccountType.ADMIN, 1L)).thenReturn(true);
        return token;
    }

    private static String loginBody(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"secret123\","
                + "\"captchaKey\":\"k\",\"captchaCode\":\"1234\"}";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
