package com.mall.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.admin.dto.AdminUserSaveRequest;
import com.mall.admin.dto.AdminUserView;
import com.mall.admin.entity.SysRoleEntity;
import com.mall.admin.entity.SysUserEntity;
import com.mall.admin.mapper.SysRoleMapper;
import com.mall.admin.mapper.SysUserMapper;
import com.mall.common.exception.BizException;
import com.mall.security.token.AccountType;
import com.mall.security.token.TokenStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 管理端用户管理服务：账号 CRUD + 角色绑定。
 *
 * <p>安全边界：内置管理员（id=1）不可停用/删除、角色不可变更，避免误操作导致平台无超管可用；
 * 停用或删除账号时同步踢下线并失效权限缓存。</p>
 */
@Service
public class AdminUserService {

    private static final Long BUILT_IN_ADMIN_ID = 1L;
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 32;
    private static final String USERNAME_EXISTS = "用户名已存在";
    private static final String USER_NOT_FOUND = "用户不存在";
    private static final String ROLE_NOT_FOUND = "角色不存在";
    private static final String BUILT_IN_PROTECTED = "内置管理员账号不可停用或删除";
    private static final String BUILT_IN_ROLE_PROTECTED = "内置管理员账号的角色不可修改";

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;

    public AdminUserService(SysUserMapper sysUserMapper,
                            SysRoleMapper sysRoleMapper,
                            PermissionService permissionService,
                            PasswordEncoder passwordEncoder,
                            TokenStore tokenStore) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
        this.tokenStore = tokenStore;
    }

    /** 全部管理员账号（含角色回显），按 ID 升序。 */
    public List<AdminUserView> list() {
        return sysUserMapper.selectList(Wrappers.<SysUserEntity>lambdaQuery()
                        .orderByAsc(SysUserEntity::getId)).stream()
                .map(user -> AdminUserView.from(user, sysRoleMapper.selectRolesByUserId(user.getId())))
                .toList();
    }

    /** 新增管理员：用户名唯一、密码 BCrypt 入库、角色全量绑定。 */
    @Transactional
    public Long create(AdminUserSaveRequest request) {
        String username = requireUsername(request.username());
        String password = requirePassword(request.password());
        ensureUsernameAvailable(username, null);
        List<Long> roleIds = normalizeRoleIds(request.roleIds());
        validateRoles(roleIds);

        SysUserEntity user = new SysUserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setStatus(normalizeStatus(request.status()));
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(USERNAME_EXISTS);
        }
        bindRoles(user.getId(), roleIds);
        return user.getId();
    }

    /** 编辑管理员：用户名不可改；密码留空不修改；角色全量覆盖（内置管理员除外）。 */
    @Transactional
    public void update(Long id, AdminUserSaveRequest request) {
        SysUserEntity user = loadUser(id);
        Integer status = normalizeStatus(request.status());
        if (BUILT_IN_ADMIN_ID.equals(id) && status == STATUS_DISABLED) {
            throw new BizException(BUILT_IN_PROTECTED);
        }

        SysUserEntity update = new SysUserEntity();
        update.setId(id);
        update.setStatus(status);
        if (request.password() != null && !request.password().isBlank()) {
            update.setPassword(passwordEncoder.encode(requirePassword(request.password())));
        }
        sysUserMapper.updateById(update);

        if (request.roleIds() != null) {
            List<Long> roleIds = normalizeRoleIds(request.roleIds());
            if (BUILT_IN_ADMIN_ID.equals(id)
                    && !roleIds.equals(sysRoleMapper.selectRoleIdsByUserId(id))) {
                throw new BizException(BUILT_IN_ROLE_PROTECTED);
            }
            validateRoles(roleIds);
            bindRoles(id, roleIds);
        }
        afterPermissionChange(user.getId(), status);
    }

    /** 启用（1）/停用（0）管理员；停用立即踢下线。 */
    @Transactional
    public void changeStatus(Long id, Integer status) {
        SysUserEntity user = loadUser(id);
        int target = normalizeStatus(status);
        if (BUILT_IN_ADMIN_ID.equals(id) && target == STATUS_DISABLED) {
            throw new BizException(BUILT_IN_PROTECTED);
        }
        SysUserEntity update = new SysUserEntity();
        update.setId(id);
        update.setStatus(target);
        sysUserMapper.updateById(update);
        afterPermissionChange(user.getId(), target);
    }

    /** 删除管理员（逻辑删除）+ 清除角色关联。 */
    @Transactional
    public void delete(Long id) {
        SysUserEntity user = loadUser(id);
        if (BUILT_IN_ADMIN_ID.equals(id)) {
            throw new BizException(BUILT_IN_PROTECTED);
        }
        sysRoleMapper.deleteUserRoles(id);
        sysUserMapper.deleteById(id);
        permissionService.evictPermissions(user.getId());
        tokenStore.kick(AccountType.ADMIN, id);
    }

    private SysUserEntity loadUser(Long id) {
        SysUserEntity user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(USER_NOT_FOUND);
        }
        return user;
    }

    private void ensureUsernameAvailable(String username, Long excludeId) {
        SysUserEntity exists = sysUserMapper.selectByUsername(username);
        if (exists != null && !Objects.equals(exists.getId(), excludeId)) {
            throw new BizException(USERNAME_EXISTS);
        }
    }

    private static String requireUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (value.length() < 3 || value.length() > 32) {
            throw new BizException("用户名长度需为 3-32 个字符");
        }
        return value;
    }

    private static String requirePassword(String password) {
        String value = password == null ? "" : password;
        if (value.length() < MIN_PASSWORD_LENGTH || value.length() > MAX_PASSWORD_LENGTH) {
            throw new BizException("密码长度需为 6-32 个字符");
        }
        return value;
    }

    private static int normalizeStatus(Integer status) {
        if (status == null) {
            return STATUS_ENABLED;
        }
        if (status != STATUS_ENABLED && status != STATUS_DISABLED) {
            throw new BizException("用户状态不合法");
        }
        return status;
    }

    private static List<Long> normalizeRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        roleIds.stream().filter(Objects::nonNull).forEach(distinct::add);
        return List.copyOf(distinct);
    }

    private void validateRoles(List<Long> roleIds) {
        for (Long roleId : roleIds) {
            SysRoleEntity role = sysRoleMapper.selectById(roleId);
            if (role == null) {
                throw new BizException(ROLE_NOT_FOUND);
            }
        }
    }

    private void bindRoles(Long userId, List<Long> roleIds) {
        sysRoleMapper.deleteUserRoles(userId);
        for (Long roleId : roleIds) {
            sysRoleMapper.insertUserRole(userId, roleId);
        }
    }

    /** 账号停用/角色变更后：踢下线（停用时）并失效权限缓存。 */
    private void afterPermissionChange(Long userId, int status) {
        permissionService.evictPermissions(userId);
        if (status == STATUS_DISABLED) {
            tokenStore.kick(AccountType.ADMIN, userId);
        }
    }
}
