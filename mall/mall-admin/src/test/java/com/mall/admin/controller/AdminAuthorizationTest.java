package com.mall.admin.controller;

import com.mall.admin.AdminTestApplication;
import com.mall.admin.dto.MenuNode;
import com.mall.admin.entity.SysUserEntity;
import com.mall.admin.mapper.SysUserMapper;
import com.mall.admin.service.PermissionService;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.mapper.ShopMapper;
import com.mall.product.mongo.AuditLogService;
import com.mall.security.jwt.JwtTokenProvider;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import com.mall.security.token.TokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端鉴权集成测试（全量上下文 + MockMvc；TokenStore / PermissionService / 管理员 mapper 均为 mock）。
 *
 * <p>钉死行为（对应 plan Task 6 Step 1 与 Review Focus 2）：</p>
 * <ul>
 *   <li>缺少 {@code system:user:list} 权限的管理员访问受保护端点 → 403</li>
 *   <li>具备该权限 → 200</li>
 *   <li>菜单端点只按当前管理员自身的权限集取节点</li>
 * </ul>
 */
@SpringBootTest(classes = AdminTestApplication.class, properties = {
        "mall.jwt.secret=" + AdminAuthorizationTest.SECRET,
        "mall.jwt.access-expire-hours=12",
        "mall.jwt.refresh-expire-days=7"
})
@AutoConfigureMockMvc
class AdminAuthorizationTest {

    static final String SECRET = "admin-authorization-test-secret-0123456789abcdef";

    private static final String USER_LIST_URL = "/api/admin/system/user/list";
    private static final String MENUS_URL = "/api/admin/auth/menus";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private TokenStore tokenStore;
    @MockBean
    private PermissionService permissionService;
    @MockBean
    private SysUserMapper sysUserMapper;
    // 用户/角色管理服务依赖的 RBAC mapper，本测试只验证鉴权与响应结构
    @MockBean
    private com.mall.admin.mapper.SysRoleMapper sysRoleMapper;
    @MockBean
    private com.mall.admin.mapper.SysPermissionMapper sysPermissionMapper;
    // M2 起上下文中新增入驻审核相关 Bean，本测试不关心其依赖，全部 mock 占位
    @MockBean
    private MerchantMapper merchantMapper;
    @MockBean
    private ShopMapper shopMapper;
    @MockBean
    private AuditLogService auditLogService;
    @MockBean
    private com.mall.product.category.CategoryService categoryService;
    @MockBean
    private com.mall.product.mapper.ProductMapper productMapper;
    @MockBean
    private com.mall.product.product.ProductCacheService productCacheService;
    // M8/M9 起 AdminProductController 增加向量重建与 AI 文案依赖，测试上下文不加载 mall-product/mall-ai，mock 占位
    @MockBean
    private com.mall.product.embedding.ProductEmbeddingService productEmbeddingService;
    @MockBean
    private com.mall.ai.gateway.AiService aiService;

    /** 无 system:user:list 权限 → 403。 */
    @Test
    void admin_without_required_permission_should_return_403() throws Exception {
        String token = givenAdminToken(Set.of("system:role:list"));

        mockMvc.perform(get(USER_LIST_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 具备 system:user:list 权限 → 200。 */
    @Test
    void admin_with_required_permission_should_return_200() throws Exception {
        String token = givenAdminToken(Set.of("system:user:list"));
        SysUserEntity admin = new SysUserEntity();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setStatus(1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));

        mockMvc.perform(get(USER_LIST_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].username").value("admin"));
    }

    /** 菜单端点只按当前管理员自身的权限集取节点。 */
    @Test
    void menus_should_only_use_current_admin_permissions() throws Exception {
        String token = givenAdminToken(Set.of("system:user:list"));
        when(permissionService.listMenus(Set.of("system:user:list")))
                .thenReturn(List.of(new MenuNode(2L, 1L, "用户管理", "system:user:list", "MENU", "/system/user", 11)));

        mockMvc.perform(get(MENUS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value("system:user:list"))
                .andExpect(jsonPath("$.data[0].path").value("/system/user"));

        verify(permissionService).listMenus(Set.of("system:user:list"));
    }

    /** 菜单端点同样受 ROLE_ADMIN 保护：买家 token 访问 → 403。 */
    @Test
    void buyer_token_calling_admin_menus_should_return_403() throws Exception {
        String token = jwtTokenProvider.createToken(9L, AccountType.USER, null);
        when(tokenStore.get(token)).thenReturn(new LoginUser(9L, AccountType.USER, null, null));
        when(tokenStore.isCurrent(token, AccountType.USER, 9L)).thenReturn(true);

        mockMvc.perform(get(MENUS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 构造一个签名合法且为当前有效登录态的管理员 token。 */
    private String givenAdminToken(Set<String> permissions) {
        String token = jwtTokenProvider.createToken(1L, AccountType.ADMIN, null);
        LoginUser admin = new LoginUser(1L, AccountType.ADMIN, null, permissions);
        when(tokenStore.get(token)).thenReturn(admin);
        when(tokenStore.isCurrent(token, AccountType.ADMIN, 1L)).thenReturn(true);
        return token;
    }
}
