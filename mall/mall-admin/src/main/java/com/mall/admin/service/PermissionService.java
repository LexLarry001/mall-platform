package com.mall.admin.service;

import com.mall.admin.dto.MenuNode;
import com.mall.admin.entity.SysPermissionEntity;
import com.mall.admin.mapper.SysPermissionMapper;
import com.mall.admin.mapper.SysRoleMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 权限服务：把"管理员 → 角色 → 权限码"解析为登录态可承载的权限集合，并按权限码裁剪菜单。
 *
 * <p>权限明细不放进 JWT，而是随登录态存 Redis（{@code LoginUser.permissions}），
 * 因此顶号/强制下线/权限变更都能立刻生效，无需等令牌过期。</p>
 */
@Service
public class PermissionService {

    /** 权限集合的 Redis 缓存前缀。 */
    private static final String PERMISSION_CACHE_PREFIX = "mall:perms:";
    /** 权限缓存有效期。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final StringRedisTemplate redis;

    public PermissionService(SysRoleMapper sysRoleMapper,
                             SysPermissionMapper sysPermissionMapper,
                             StringRedisTemplate redis) {
        this.sysRoleMapper = sysRoleMapper;
        this.sysPermissionMapper = sysPermissionMapper;
        this.redis = redis;
    }

    /**
     * 加载管理员权限码集合（多角色取并集去重）。
     *
     * <p>优先读 Redis 缓存（{@code mall:perms:{adminId}}，TTL 30min），未命中再查库并回填。</p>
     *
     * @return 权限码集合；管理员无任何角色时返回空集
     */
    public Set<String> loadPermissions(Long adminId) {
        Set<String> cached = redis.opsForSet().members(PERMISSION_CACHE_PREFIX + adminId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        List<Long> roleIds = sysRoleMapper.selectRoleIdsByUserId(adminId);
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> codes = permissionCodesOf(roleIds);
        cache(adminId, codes);
        return codes;
    }

    /**
     * 取菜单节点：只返回调用方权限码内的 {@code MENU} 节点，按 {@code sort} 升序。
     *
     * @param permissionCodes 当前管理员的权限码集合，为空则返回空列表
     */
    public List<MenuNode> listMenus(Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return List.of();
        }
        return sysPermissionMapper.selectMenusByCodes(permissionCodes).stream()
                .map(MenuNode::from)
                .toList();
    }

    private Set<String> permissionCodesOf(List<Long> roleIds) {
        List<Long> permissionIds = sysPermissionMapper.selectPermissionIdsByRoleIds(roleIds);
        if (permissionIds == null || permissionIds.isEmpty()) {
            return Set.of();
        }
        return sysPermissionMapper.selectEnabledByIds(permissionIds).stream()
                .map(SysPermissionEntity::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 写入权限缓存；空集不缓存，避免把"无权限"固化 30 分钟。 */
    private void cache(Long adminId, Set<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        String key = PERMISSION_CACHE_PREFIX + adminId;
        redis.opsForSet().add(key, codes.toArray(new String[0]));
        redis.expire(key, CACHE_TTL);
    }

    /** 失效指定管理员的权限缓存（用户角色变更、账号停用/删除时调用）。 */
    public void evictPermissions(Long adminId) {
        redis.delete(PERMISSION_CACHE_PREFIX + adminId);
    }

    /** 失效拥有该角色的全部管理员的权限缓存（角色权限/状态变更时调用）。 */
    public void evictPermissionsByRole(Long roleId) {
        List<Long> userIds = sysRoleMapper.selectUserIdsByRoleId(roleId);
        if (userIds == null) {
            return;
        }
        userIds.forEach(this::evictPermissions);
    }
}
