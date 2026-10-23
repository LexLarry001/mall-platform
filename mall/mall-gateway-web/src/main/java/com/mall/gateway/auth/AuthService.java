package com.mall.gateway.auth;

import com.mall.admin.entity.SysUserEntity;
import com.mall.admin.mapper.SysUserMapper;
import com.mall.admin.service.PermissionService;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 三端认证服务：注册、登录、（登录态）刷新、退出。
 *
 * <p>认证要点：</p>
 * <ul>
 *   <li>登录先校验图形验证码，验证码错误/过期一律不签发任何令牌</li>
 *   <li>账号不存在与密码错误返回同一文案，不泄露账号是否存在</li>
 *   <li>密码以 BCrypt 存储，入库前绝不保留明文</li>
 *   <li>注销与强制下线同时清除 access / refresh 指针，避免"退出后靠 refresh 复活"</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";
    private static final String USERNAME_EXISTS_MESSAGE = "用户名已存在";
    private static final String ACCOUNT_DISABLED_MESSAGE = "账号已被冻结";
    private static final String ADMIN_REGISTER_FORBIDDEN_MESSAGE = "管理员账号不支持自助注册";

    /** 账号可用状态。 */
    private static final int STATUS_ENABLED = 1;
    /** 商家入驻审核中。 */
    private static final int AUDIT_STATUS_PENDING = 0;

    private final CaptchaService captchaService;
    private final TokenStore tokenStore;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final MerchantMapper merchantMapper;
    private final SysUserMapper sysUserMapper;
    private final PermissionService permissionService;
    private final ShopMapper shopMapper;

    @Value("${mall.jwt.access-expire-hours:12}")
    private long accessExpireHours = 12;

    @Value("${mall.jwt.refresh-expire-days:7}")
    private long refreshExpireDays = 7;

    public AuthService(CaptchaService captchaService,
                       TokenStore tokenStore,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       UserMapper userMapper,
                       MerchantMapper merchantMapper,
                       SysUserMapper sysUserMapper,
                       PermissionService permissionService,
                       ShopMapper shopMapper) {
        this.captchaService = captchaService;
        this.tokenStore = tokenStore;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.merchantMapper = merchantMapper;
        this.sysUserMapper = sysUserMapper;
        this.permissionService = permissionService;
        this.shopMapper = shopMapper;
    }

    /** 注册（买家 / 商家）。商家注册后为待审核状态，可登录但暂无店铺。 */
    public void register(AccountType type, RegisterRequest request) {
        switch (type) {
            case USER -> registerUser(request);
            case MERCHANT -> registerMerchant(request);
            case ADMIN -> throw new BizException(ADMIN_REGISTER_FORBIDDEN_MESSAGE);
        }
    }

    /** 登录：验证码 → 查账号 → 校验密码与状态 → 签发令牌并写入登录态。 */
    public LoginResponse login(AccountType type, LoginRequest request) {
        captchaService.verify(request.captchaKey(), request.captchaCode());

        Account account = findByUsername(type, request.username());
        if (account == null || !passwordEncoder.matches(request.password(), account.password())) {
            throw new BizException(LOGIN_FAILED_MESSAGE);
        }
        if (account.status() != STATUS_ENABLED) {
            throw new BizException(ACCOUNT_DISABLED_MESSAGE);
        }
        return issueTokens(type, account.accountId(), account.shopId());
    }

    /** 刷新登录态：校验刷新令牌签名、类别与 Redis 指针后换发新的令牌对（旧刷新令牌作废）。 */
    public LoginResponse refresh(String refreshToken) {
        AccountType type;
        Long accountId;
        try {
            Claims claims = jwtTokenProvider.parse(refreshToken);
            if (!jwtTokenProvider.isRefreshToken(claims)) {
                throw unauthorized();
            }
            type = AccountType.fromName(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class));
            accountId = claims.getSubject() == null ? null : Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            throw unauthorized();
        }
        if (type == null || accountId == null || !tokenStore.isCurrentRefresh(refreshToken, type, accountId)) {
            throw unauthorized();
        }

        Account account = findById(type, accountId);
        if (account == null) {
            throw unauthorized();
        }
        if (account.status() != STATUS_ENABLED) {
            throw new BizException(ACCOUNT_DISABLED_MESSAGE);
        }
        return issueTokens(type, accountId, account.shopId());
    }

    /** 退出登录：清除该令牌对应的登录态与刷新令牌指针。 */
    public void logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        tokenStore.remove(accessToken);
    }

    private void registerUser(RegisterRequest request) {
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new BizException(USERNAME_EXISTS_MESSAGE);
        }
        UserEntity entity = new UserEntity();
        entity.setUsername(request.username());
        entity.setPassword(passwordEncoder.encode(request.password()));
        entity.setStatus(STATUS_ENABLED);
        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // 并发注册下的兜底：唯一键冲突也走业务文案，不把数据库细节抛给前端
            throw new BizException(USERNAME_EXISTS_MESSAGE);
        }
    }

    private void registerMerchant(RegisterRequest request) {
        if (merchantMapper.selectByUsername(request.username()) != null) {
            throw new BizException(USERNAME_EXISTS_MESSAGE);
        }
        MerchantEntity entity = new MerchantEntity();
        entity.setUsername(request.username());
        entity.setPassword(passwordEncoder.encode(request.password()));
        entity.setContactPhone(request.contactPhone());
        entity.setAuditStatus(AUDIT_STATUS_PENDING);
        entity.setStatus(STATUS_ENABLED);
        try {
            merchantMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            throw new BizException(USERNAME_EXISTS_MESSAGE);
        }
    }

    private LoginResponse issueTokens(AccountType type, Long accountId, Long shopId) {
        String accessToken = jwtTokenProvider.createToken(accountId, type, shopId);
        String refreshToken = jwtTokenProvider.createRefreshToken(accountId, type);

        // 权限明细随登录态存 Redis（不放进 JWT），管理员在签发时装载 RBAC 权限集，其余端为空
        Set<String> permissions = type == AccountType.ADMIN ? permissionService.loadPermissions(accountId) : null;
        LoginUser loginUser = new LoginUser(accountId, type, shopId, permissions);
        tokenStore.save(accessToken, loginUser, Duration.ofHours(accessExpireHours));
        tokenStore.saveRefresh(refreshToken, type, accountId, Duration.ofDays(refreshExpireDays));

        return new LoginResponse(accessToken, refreshToken, accountId, type.name(), shopId);
    }

    private Account findByUsername(AccountType type, String username) {
        return switch (type) {
            case USER -> fromUser(userMapper.selectByUsername(username));
            case MERCHANT -> fromMerchant(merchantMapper.selectByUsername(username));
            case ADMIN -> fromSysUser(sysUserMapper.selectByUsername(username));
        };
    }

    private Account findById(AccountType type, Long accountId) {
        return switch (type) {
            case USER -> fromUser(userMapper.selectById(accountId));
            case MERCHANT -> fromMerchant(merchantMapper.selectById(accountId));
            case ADMIN -> fromSysUser(sysUserMapper.selectById(accountId));
        };
    }

    private static Account fromUser(UserEntity entity) {
        return entity == null ? null
                : new Account(entity.getId(), entity.getPassword(), entity.getStatus(), null);
    }

    /**
     * 商家账号转登录视图：已开店的绑定 shopId（进 JWT 与登录态），未开店为 {@code null}。
     *
     * <p>登录与刷新两条路径都会走到这里，因此"审核通过后重新登录/刷新即得 shopId"。</p>
     */
    private Account fromMerchant(MerchantEntity entity) {
        if (entity == null) {
            return null;
        }
        ShopEntity shop = shopMapper.selectByMerchantId(entity.getId());
        Long shopId = shop == null ? null : shop.getId();
        return new Account(entity.getId(), entity.getPassword(), entity.getStatus(), shopId);
    }

    private static Account fromSysUser(SysUserEntity entity) {
        return entity == null ? null
                : new Account(entity.getId(), entity.getPassword(), entity.getStatus(), null);
    }

    private static BizException unauthorized() {
        return new BizException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
    }

    /**
     * 账号校验快照。
     *
     * @param accountId 账号 ID
     * @param password  BCrypt 密码哈希
     * @param status    账号状态
     * @param shopId    店铺 ID（M1 阶段为 null）
     */
    private record Account(Long accountId, String password, int status, Long shopId) {
    }
}
