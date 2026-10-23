package com.mall.gateway.admin;

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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台端用户管理与角色管理 HTTP 链路测试。
 *
 * <p>覆盖菜单 {@code /system/user}、{@code /system/role} 背后的全部接口：账号 CRUD 与角色绑定、
 * 角色 CRUD 与权限授权、权限码不足 403、内置管理员/超级角色保护、角色占用校验。
 * 真实安全过滤链 + 真实 MySQL/Redis，令牌用真实 JWT 签发并 mock 登录态。</p>
 */
@SpringBootTest(
        classes = AdminRbacFlowTest.TestApp.class,
        properties = {
                "mall.jwt.secret=" + AdminRbacFlowTest.SECRET,
                "mall.jwt.access-expire-hours=12",
                "mall.jwt.refresh-expire-days=7",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.url=jdbc:mysql://localhost:3306/mall?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456"
        })
@AutoConfigureMockMvc
class AdminRbacFlowTest {

    static final String SECRET = "admin-rbac-flow-test-secret-0123456789";
    private static final String SUPER = "ROLE_SUPER";
    private static final String ROLE_CODE = "ROLE_RBTEST_OPS";
    private static final String USERNAME = "rbtest_user";
    private static final String ROLE_USERNAME = "rbtest_role_user";

    /** 高权管理员：拥有用户/角色管理全部权限码。 */
    private static final String[] ALL_PERMISSIONS = {
            "system:user:list", "system:user:add", "system:user:edit", "system:user:delete",
            "system:role:list", "system:role:add", "system:role:edit", "system:role:delete"
    };

    @SpringBootApplication(scanBasePackages = {
            "com.mall.admin.controller", "com.mall.admin.service",
            "com.mall.security", "com.mall.gateway.config"})
    @MapperScan("com.mall.admin.mapper")
    @Import(GlobalExceptionHandler.class)
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

    private String adminToken;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        adminToken = token(1L, ALL_PERMISSIONS);
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // ------------------------------------------------------------ 用户管理

    /** 新增 → 列表回显角色 → 编辑/停用 → 删除 的完整链路。 */
    @Test
    void admin_user_crud_full_chain() throws Exception {
        mockMvc.perform(get("/api/admin/system/user/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[*].username", hasItem("admin")));

        String userId = createUser(USERNAME, "secret123", 1, List.of(roleId(SUPER)));

        mockMvc.perform(get("/api/admin/system/user/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[*].username", hasItem(USERNAME)));

        assertThat(roleNamesOf(Long.valueOf(userId))).containsExactly("超级管理员");
        // 密码必须 BCrypt 入库，不能是明文
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE id = ?", String.class, Long.valueOf(userId)))
                .startsWith("$2");

        // 编辑：用户名不可改、密码留空不修改、停用 + 清空角色
        mockMvc.perform(put("/api/admin/system/user/{id}", userId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"renamed\",\"password\":\"\",\"status\":0,\"roleIds\":[]}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(userStatus(Long.valueOf(userId))).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_user_role WHERE user_id = ?", Long.valueOf(userId))).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT username FROM sys_user WHERE id = ?", String.class, Long.valueOf(userId)))
                .isEqualTo(USERNAME);
        // 停用会调用 TokenStore.kick，这里只验证不抛异常（Mock 已就绪）

        mockMvc.perform(post("/api/admin/system/user/{id}/status", userId)
                        .param("status", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(userStatus(Long.valueOf(userId))).isEqualTo(1);

        mockMvc.perform(delete("/api/admin/system/user/{id}", userId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(count("SELECT COUNT(*) FROM sys_user WHERE id = ? AND deleted = 0",
                Long.valueOf(userId))).isZero();

        // 重复用户名被拒
        mockMvc.perform(post("/api/admin/system/user")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("rbtest_dup", "secret123", 1, List.of())))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/admin/system/user")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("rbtest_dup", "secret123", 1, List.of())))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("用户名已存在")));
    }

    /** 权限码不足：可看列表，不可新增/删除，也不能进角色管理。 */
    @Test
    void low_permission_admin_is_forbidden_for_write_operations() throws Exception {
        String lowToken = token(2L, "system:user:list");

        mockMvc.perform(get("/api/admin/system/user/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lowToken)))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/admin/system/user")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lowToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("rbtest_low", "secret123", 1, List.of())))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/admin/system/user/{id}", 999L)
                        .header(HttpHeaders.AUTHORIZATION, bearer(lowToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/system/role/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(lowToken)))
                .andExpect(status().isForbidden());
    }

    /** 用户参数校验：用户名长度、角色存在性。 */
    @Test
    void admin_user_create_rejects_invalid_input() throws Exception {
        mockMvc.perform(post("/api/admin/system/user")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("ab", "secret123", 1, List.of())))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("用户名长度需为 3-32")));

        mockMvc.perform(post("/api/admin/system/user")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("rbtest_badrole", "secret123", 1, List.of(999_999L))))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("角色不存在")));
    }

    // ------------------------------------------------------------ 角色管理

    /** 新增角色并授权 → 授权树 → 编辑覆盖 → 占用时不可删 → 释放后可删。 */
    @Test
    void admin_role_crud_and_permission_assign_chain() throws Exception {
        mockMvc.perform(get("/api/admin/system/role/permission/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[*].code", hasItem("system:user:list")))
                .andExpect(jsonPath("$.data[*].code", hasItem("system:role:delete")));

        String roleId = createRole("RB测试运营", ROLE_CODE, 1, List.of(1L, 2L));
        assertThat(permissionCountOf(Long.valueOf(roleId))).isEqualTo(2);

        mockMvc.perform(get("/api/admin/system/role/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[*].code", hasItem(ROLE_CODE)));

        // 编辑：改名 + 全量覆盖授权
        mockMvc.perform(put("/api/admin/system/role/{id}", roleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson("RB测试运营改", ROLE_CODE, 1, List.of(1L, 2L, 3L))))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(permissionCountOf(Long.valueOf(roleId))).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM sys_role WHERE id = ?", String.class, Long.valueOf(roleId)))
                .isEqualTo("RB测试运营改");

        // 角色已分配给管理员 → 拒绝删除
        createUser(ROLE_USERNAME, "secret123", 1, List.of(Long.valueOf(roleId)));
        mockMvc.perform(delete("/api/admin/system/role/{id}", roleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("角色已分配给管理员")));

        // 释放后删除成功，关联权限一并清理
        Long roleUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ? AND deleted = 0", Long.class, ROLE_USERNAME);
        mockMvc.perform(delete("/api/admin/system/user/{id}", roleUserId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(delete("/api/admin/system/role/{id}", roleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(count("SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ?",
                Long.valueOf(roleId))).isZero();
    }

    /** 角色参数校验：ROLE_ 前缀与唯一性。 */
    @Test
    void admin_role_create_rejects_invalid_code() throws Exception {
        mockMvc.perform(post("/api/admin/system/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson("测试角色", "OPS", 1, List.of())))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("角色标识必须以 ROLE_ 开头")));

        createRole("RB测试运营", ROLE_CODE, 1, List.of(1L));
        mockMvc.perform(post("/api/admin/system/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson("重复角色", ROLE_CODE, 1, List.of())))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("角色标识已存在")));
    }

    /** 内置管理员账号与内置超级角色不可停用/删除/改角色。 */
    @Test
    void built_in_admin_and_super_role_are_protected() throws Exception {
        mockMvc.perform(post("/api/admin/system/user/{id}/status", 1L)
                        .param("status", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("内置管理员账号")));

        mockMvc.perform(delete("/api/admin/system/user/{id}", 1L)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("内置管理员账号")));

        mockMvc.perform(put("/api/admin/system/user/{id}", 1L)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("admin", "", 1, List.of())))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("角色不可修改")));

        Long superRoleId = roleId(SUPER);
        mockMvc.perform(put("/api/admin/system/role/{id}", superRoleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson("超级管理员改", SUPER, 1, List.of(1L))))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("内置超级管理员角色")));

        mockMvc.perform(post("/api/admin/system/role/{id}/status", superRoleId)
                        .param("status", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("内置超级管理员角色")));

        mockMvc.perform(delete("/api/admin/system/role/{id}", superRoleId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(containsString("内置超级管理员角色")));
    }

    // ------------------------------------------------------------------ helpers

    private String token(Long accountId, String... permissions) {
        String token = jwtTokenProvider.createToken(accountId, AccountType.ADMIN, null);
        when(tokenStore.get(token)).thenReturn(
                new LoginUser(accountId, AccountType.ADMIN, null, Set.of(permissions)));
        when(tokenStore.isCurrent(eq(token), eq(AccountType.ADMIN), eq(accountId))).thenReturn(true);
        return token;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String createUser(String username, String password, int status, List<Long> roleIds) throws Exception {
        String response = mockMvc.perform(post("/api/admin/system/user")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(username, password, status, roleIds)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Object) com.jayway.jsonpath.JsonPath.read(response, "$.data"));
    }

    private String createRole(String name, String code, int status, List<Long> permissionIds) throws Exception {
        String response = mockMvc.perform(post("/api/admin/system/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleJson(name, code, status, permissionIds)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Object) com.jayway.jsonpath.JsonPath.read(response, "$.data"));
    }

    private static String userJson(String username, String password, int status, List<Long> roleIds) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\""
                + ",\"status\":" + status + ",\"roleIds\":" + roleIds + "}";
    }

    private static String roleJson(String name, String code, int status, List<Long> permissionIds) {
        return "{\"name\":\"" + name + "\",\"code\":\"" + code + "\""
                + ",\"status\":" + status + ",\"permissionIds\":" + permissionIds + "}";
    }

    private Long roleId(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_role WHERE code = ? AND deleted = 0", Long.class, code);
    }

    private List<String> roleNamesOf(Long userId) {
        return jdbcTemplate.queryForList("SELECT r.name FROM sys_role r "
                + "JOIN sys_user_role ur ON ur.role_id = r.id "
                + "WHERE ur.user_id = ? AND r.deleted = 0 ORDER BY r.id", String.class, userId);
    }

    private Integer userStatus(Long userId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    private Integer permissionCountOf(Long roleId) {
        return count("SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ?", roleId);
    }

    private Integer count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'rbtest\\_%')");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'rbtest\\_%'");
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id IN "
                + "(SELECT id FROM sys_role WHERE code LIKE 'ROLE\\_RBTEST%')");
        jdbcTemplate.update("DELETE FROM sys_role WHERE code LIKE 'ROLE\\_RBTEST%'");
    }
}
