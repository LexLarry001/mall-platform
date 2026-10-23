package com.mall.gateway.auth;

import com.mall.admin.entity.SysUserEntity;
import com.mall.admin.mapper.SysUserMapper;
import com.mall.admin.service.PermissionService;
import com.mall.common.exception.BizException;
import com.mall.gateway.auth.dto.LoginRequest;
import com.mall.gateway.auth.dto.LoginResponse;
import com.mall.gateway.auth.dto.RegisterRequest;
import com.mall.merchant.entity.MerchantEntity;
import com.mall.merchant.mapper.MerchantMapper;
import com.mall.product.entity.ShopEntity;
import com.mall.product.mapper.ShopMapper;
import com.mall.security.captcha.CaptchaService;
import com.mall.security.jwt.JwtTokenProvider;
import com.mall.security.token.AccountType;
import com.mall.security.token.LoginUser;
import com.mall.security.token.TokenStore;
import com.mall.user.entity.UserEntity;
import com.mall.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试（mapper 与 Redis 登录态均为 mock，JWT 用真实实现）。
 *
 * <p>钉死行为（对应 plan Review Focus 4/5 与顶号）：</p>
 * <ul>
 *   <li>验证码错误 → 业务异常且不签发任何 token</li>
 *   <li>用户不存在与密码错误 → 同一文案，不泄露账号是否存在</li>
 *   <li>重复用户名注册 → 业务异常"用户名已存在"，不落库、不暴露唯一键冲突</li>
 *   <li>正常登录 → 签发 access+refresh 且写入 Redis 登录态</li>
 *   <li>刷新：合法 → 换发新令牌对；伪造/已失效 → 401</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "auth-service-test-secret-0123456789abcdefghij";

    @Mock
    private CaptchaService captchaService;
    @Mock
    private TokenStore tokenStore;
    @Mock
    private UserMapper userMapper;
    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private ShopMapper shopMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET, 12, 7);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(captchaService, tokenStore, jwtTokenProvider, passwordEncoder,
                userMapper, merchantMapper, sysUserMapper, permissionService, shopMapper);
    }

    @Test
    void login_with_wrong_captcha_should_fail_and_issue_no_token() {
        doThrow(new BizException("验证码错误或已过期"))
                .when(captchaService).verify("captcha-key", "0000");

        assertThatThrownBy(() -> authService.login(AccountType.USER,
                loginRequest("buyer", "secret", "captcha-key", "0000")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码");

        verifyNoInteractions(userMapper);
        verifyNoInteractions(tokenStore);
    }

    @Test
    void unknown_user_and_wrong_password_should_share_the_same_message() {
        when(userMapper.selectByUsername("ghost")).thenReturn(null);

        BizException unknown = catchThrowableOfType(
                () -> authService.login(AccountType.USER, loginRequest("ghost", "secret", "captcha-key", "1234")),
                BizException.class);

        when(userMapper.selectByUsername("bob")).thenReturn(user(1L, "bob", passwordEncoder.encode("secret"), 1));
        BizException wrongPassword = catchThrowableOfType(
                () -> authService.login(AccountType.USER, loginRequest("bob", "wrong", "captcha-key", "1234")),
                BizException.class);

        assertThat(unknown).isNotNull();
        assertThat(wrongPassword).isNotNull();
        assertThat(unknown.getMessage()).isEqualTo(wrongPassword.getMessage());
        verifyNoInteractions(tokenStore);
    }

    @Test
    void register_with_existing_username_should_fail() {
        when(userMapper.selectByUsername("bob")).thenReturn(user(1L, "bob", "hashed", 1));

        assertThatThrownBy(() -> authService.register(AccountType.USER,
                new RegisterRequest("bob", "secret", null)))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名已存在");

        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void register_should_store_bcrypt_password_and_never_plain_text() {
        when(userMapper.selectByUsername("newbie")).thenReturn(null);

        authService.register(AccountType.USER, new RegisterRequest("newbie", "secret", null));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("newbie");
        assertThat(captor.getValue().getPassword()).isNotEqualTo("secret");
        assertThat(passwordEncoder.matches("secret", captor.getValue().getPassword())).isTrue();
    }

    @Test
    void login_should_issue_token_pair_and_persist_login_state() {
        when(userMapper.selectByUsername("bob")).thenReturn(user(1L, "bob", passwordEncoder.encode("secret"), 1));

        LoginResponse response = authService.login(AccountType.USER, loginRequest("bob", "secret", "k", "1234"));

        assertThat(response.accountType()).isEqualTo("USER");
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.shopId()).isNull();

        ArgumentCaptor<LoginUser> loginUserCaptor = ArgumentCaptor.forClass(LoginUser.class);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(tokenStore).save(tokenCaptor.capture(), loginUserCaptor.capture(), eq(Duration.ofHours(12)));

        assertThat(tokenCaptor.getValue()).isEqualTo(response.accessToken());
        assertThat(loginUserCaptor.getValue().accountId()).isEqualTo(1L);
        assertThat(loginUserCaptor.getValue().type()).isEqualTo(AccountType.USER);
        assertThat(jwtTokenProvider.parse(response.accessToken()).getSubject()).isEqualTo("1");
        assertThat(jwtTokenProvider.isRefreshToken(jwtTokenProvider.parse(response.refreshToken()))).isTrue();
        verify(tokenStore).saveRefresh(response.refreshToken(), AccountType.USER, 1L, Duration.ofDays(7));
    }

    @Test
    void admin_login_should_load_rbac_permissions_into_login_state() {
        SysUserEntity admin = new SysUserEntity();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setStatus(1);
        when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of("system:user:list"));

        authService.login(AccountType.ADMIN, loginRequest("admin", "admin123", "k", "1234"));

        ArgumentCaptor<LoginUser> captor = ArgumentCaptor.forClass(LoginUser.class);
        verify(tokenStore).save(any(String.class), captor.capture(), eq(Duration.ofHours(12)));
        assertThat(captor.getValue().permissions()).containsExactly("system:user:list");
    }

    @Test
    void buyer_login_should_not_touch_permission_service() {
        when(userMapper.selectByUsername("bob")).thenReturn(user(1L, "bob", passwordEncoder.encode("secret"), 1));

        authService.login(AccountType.USER, loginRequest("bob", "secret", "k", "1234"));

        verifyNoInteractions(permissionService);
    }

    @Test
    void refresh_with_valid_token_should_rotate_the_pair() {
        String refreshToken = jwtTokenProvider.createRefreshToken(1L, AccountType.USER);
        when(tokenStore.isCurrentRefresh(refreshToken, AccountType.USER, 1L)).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(user(1L, "bob", "hashed", 1));

        LoginResponse response = authService.refresh(refreshToken);

        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.accountType()).isEqualTo("USER");
        assertThat(response.refreshToken()).isNotEqualTo(refreshToken);
        assertThat(jwtTokenProvider.parse(response.accessToken()).getSubject()).isEqualTo("1");
        verify(tokenStore).saveRefresh(eq(response.refreshToken()), eq(AccountType.USER), eq(1L), any(Duration.class));
    }

    @Test
    void refresh_with_forged_token_should_return_401() {
        assertThatThrownBy(() -> authService.refresh("not-a-real-jwt"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(401);
        verifyNoInteractions(tokenStore);
    }

    @Test
    void refresh_with_superseded_token_should_return_401() {
        String refreshToken = jwtTokenProvider.createRefreshToken(1L, AccountType.USER);
        when(tokenStore.isCurrentRefresh(refreshToken, AccountType.USER, 1L)).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(refreshToken)).isInstanceOf(BizException.class);
        verify(tokenStore, never()).save(any(), any(), any());
    }

    @Test
    void logout_should_remove_login_state() {
        authService.logout("access-a");

        verify(tokenStore).remove("access-a");
    }

    @Test
    void merchant_login_should_bind_shop_id_into_token_and_login_state() {
        when(merchantMapper.selectByUsername("seller"))
                .thenReturn(merchant(7L, "seller", passwordEncoder.encode("secret"), 1));
        when(shopMapper.selectByMerchantId(7L)).thenReturn(shop(99L, 7L));

        LoginResponse response = authService.login(AccountType.MERCHANT, loginRequest("seller", "secret", "k", "1234"));

        assertThat(response.shopId()).isEqualTo(99L);
        ArgumentCaptor<LoginUser> captor = ArgumentCaptor.forClass(LoginUser.class);
        verify(tokenStore).save(any(String.class), captor.capture(), eq(Duration.ofHours(12)));
        assertThat(captor.getValue().shopId()).isEqualTo(99L);
        assertThat(jwtTokenProvider.parse(response.accessToken()).get(JwtTokenProvider.CLAIM_SHOP_ID, Long.class))
                .isEqualTo(99L);
    }

    @Test
    void merchant_login_without_shop_should_keep_shop_id_null() {
        when(merchantMapper.selectByUsername("seller"))
                .thenReturn(merchant(7L, "seller", passwordEncoder.encode("secret"), 1));
        when(shopMapper.selectByMerchantId(7L)).thenReturn(null);

        LoginResponse response = authService.login(AccountType.MERCHANT, loginRequest("seller", "secret", "k", "1234"));

        assertThat(response.shopId()).isNull();
    }

    @Test
    void frozen_merchant_login_should_fail_with_frozen_message() {
        when(merchantMapper.selectByUsername("seller"))
                .thenReturn(merchant(7L, "seller", passwordEncoder.encode("secret"), 0));
        when(shopMapper.selectByMerchantId(7L)).thenReturn(null);

        assertThatThrownBy(() -> authService.login(AccountType.MERCHANT, loginRequest("seller", "secret", "k", "1234")))
                .isInstanceOf(BizException.class)
                .hasMessage("账号已被冻结");
        verifyNoInteractions(tokenStore);
    }

    @Test
    void merchant_refresh_should_rebind_latest_shop_id() {
        String refreshToken = jwtTokenProvider.createRefreshToken(7L, AccountType.MERCHANT);
        when(tokenStore.isCurrentRefresh(refreshToken, AccountType.MERCHANT, 7L)).thenReturn(true);
        when(merchantMapper.selectById(7L)).thenReturn(merchant(7L, "seller", "hashed", 1));
        when(shopMapper.selectByMerchantId(7L)).thenReturn(shop(99L, 7L));

        LoginResponse response = authService.refresh(refreshToken);

        assertThat(response.shopId()).isEqualTo(99L);
    }

    private static LoginRequest loginRequest(String username, String password, String captchaKey, String captchaCode) {
        return new LoginRequest(username, password, captchaKey, captchaCode);
    }

    private static UserEntity user(long id, String username, String password, int status) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setPassword(password);
        entity.setStatus(status);
        return entity;
    }

    private static MerchantEntity merchant(long id, String username, String password, int status) {
        MerchantEntity entity = new MerchantEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setPassword(password);
        entity.setStatus(status);
        return entity;
    }

    private static ShopEntity shop(long shopId, long merchantId) {
        ShopEntity entity = new ShopEntity();
        entity.setId(shopId);
        entity.setMerchantId(merchantId);
        return entity;
    }
}
