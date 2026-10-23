package com.mall.admin.service;

import com.mall.admin.dto.MenuNode;
import com.mall.admin.entity.SysPermissionEntity;
import com.mall.admin.mapper.SysPermissionMapper;
import com.mall.admin.mapper.SysRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RBAC 权限服务单元测试（mapper 与 Redis 均为 mock）。
 *
 * <p>钉死行为：多角色权限取并集并去重；无角色 → 空集且不写库/不缓存；菜单只按传入的权限码取。</p>
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysPermissionMapper sysPermissionMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOperations;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(sysRoleMapper, sysPermissionMapper, redis);
        // opsForSet 仅在"有权限"时被调用，lenient 避免无角色用例报无效桩
        lenient().when(redis.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void multiple_roles_should_union_their_permissions() {
        when(sysRoleMapper.selectRoleIdsByUserId(7L)).thenReturn(List.of(1L, 2L));
        when(sysPermissionMapper.selectPermissionIdsByRoleIds(anyCollection())).thenReturn(List.of(10L, 11L, 12L));
        when(sysPermissionMapper.selectEnabledByIds(anyCollection()))
                .thenReturn(List.of(permission("system:user:list"), permission("system:role:list"),
                        permission("system:user:add")));

        Set<String> permissions = permissionService.loadPermissions(7L);

        assertThat(permissions).containsExactlyInAnyOrder(
                "system:user:list", "system:role:list", "system:user:add");
        verify(redis).expire(eq("mall:perms:7"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void duplicated_permission_codes_should_be_deduplicated() {
        when(sysRoleMapper.selectRoleIdsByUserId(7L)).thenReturn(List.of(1L, 2L));
        when(sysPermissionMapper.selectPermissionIdsByRoleIds(anyCollection())).thenReturn(List.of(10L, 10L));
        when(sysPermissionMapper.selectEnabledByIds(anyCollection()))
                .thenReturn(List.of(permission("a"), permission("a")));

        Set<String> permissions = permissionService.loadPermissions(7L);

        assertThat(permissions).containsExactly("a");
    }

    @Test
    void admin_without_role_should_get_empty_permissions_and_write_nothing() {
        when(sysRoleMapper.selectRoleIdsByUserId(7L)).thenReturn(List.of());

        Set<String> permissions = permissionService.loadPermissions(7L);

        assertThat(permissions).isEmpty();
        verifyNoInteractions(sysPermissionMapper);
        verify(redis, never()).expire(any(), any(Duration.class));
    }

    @Test
    void list_menus_should_map_permission_rows_to_menu_nodes() {
        SysPermissionEntity userMenu = permission("system:user:list");
        userMenu.setId(2L);
        userMenu.setParentId(1L);
        userMenu.setName("用户管理");
        userMenu.setType("MENU");
        userMenu.setPath("/system/user");
        userMenu.setSort(11);
        when(sysPermissionMapper.selectMenusByCodes(anyCollection())).thenReturn(List.of(userMenu));

        List<MenuNode> menus = permissionService.listMenus(Set.of("system:user:list"));

        assertThat(menus).hasSize(1);
        MenuNode node = menus.get(0);
        assertThat(node.id()).isEqualTo(2L);
        assertThat(node.parentId()).isEqualTo(1L);
        assertThat(node.name()).isEqualTo("用户管理");
        assertThat(node.code()).isEqualTo("system:user:list");
        assertThat(node.type()).isEqualTo("MENU");
        assertThat(node.path()).isEqualTo("/system/user");
        assertThat(node.sort()).isEqualTo(11);
    }

    @Test
    void list_menus_with_empty_permissions_should_return_empty_without_query() {
        assertThat(permissionService.listMenus(Set.of())).isEmpty();
        assertThat(permissionService.listMenus(null)).isEmpty();
        verifyNoInteractions(sysPermissionMapper);
    }

    @Test
    void cached_permissions_should_be_reused_without_querying_db() {
        when(setOperations.members("mall:perms:7")).thenReturn(Set.of("cached:code"));

        Set<String> permissions = permissionService.loadPermissions(7L);

        assertThat(permissions).containsExactly("cached:code");
        verifyNoInteractions(sysRoleMapper);
        verifyNoInteractions(sysPermissionMapper);
    }

    @Test
    void empty_cache_should_fall_through_to_db() {
        when(setOperations.members("mall:perms:7")).thenReturn(Set.of());
        when(sysRoleMapper.selectRoleIdsByUserId(7L)).thenReturn(List.of());

        assertThat(permissionService.loadPermissions(7L)).isEmpty();
    }

    private static SysPermissionEntity permission(String code) {
        SysPermissionEntity entity = new SysPermissionEntity();
        entity.setCode(code);
        return entity;
    }
}
