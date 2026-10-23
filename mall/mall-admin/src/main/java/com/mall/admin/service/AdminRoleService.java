package com.mall.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.admin.dto.AdminRoleSaveRequest;
import com.mall.admin.dto.AdminRoleView;
import com.mall.admin.dto.PermissionView;
import com.mall.admin.entity.SysPermissionEntity;
import com.mall.admin.entity.SysRoleEntity;
import com.mall.admin.mapper.SysPermissionMapper;
import com.mall.admin.mapper.SysRoleMapper;
import com.mall.common.exception.BizException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 管理端角色管理服务：角色 CRUD + 权限全量授权。
 *
 * <p>安全边界：内置超级管理员角色（{@code ROLE_SUPER}）不可编辑/停用/删除，
 * 避免把平台锁死在无权限状态；角色权限或状态变更后立即失效相关管理员的权限缓存。</p>
 */
@Service
public class AdminRoleService {

    private static final String SUPER_ROLE_CODE = "ROLE_SUPER";
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;
    private static final String ROLE_CODE_PREFIX = "ROLE_";
    private static final String ROLE_NOT_FOUND = "角色不存在";
    private static final String ROLE_CODE_EXISTS = "角色标识已存在";
    private static final String SUPER_ROLE_PROTECTED = "内置超级管理员角色不可修改或删除";
    private static final String ROLE_IN_USE = "角色已分配给管理员，无法删除";

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final PermissionService permissionService;

    public AdminRoleService(SysRoleMapper sysRoleMapper,
                            SysPermissionMapper sysPermissionMapper,
                            PermissionService permissionService) {
        this.sysRoleMapper = sysRoleMapper;
        this.sysPermissionMapper = sysPermissionMapper;
        this.permissionService = permissionService;
    }

    /** 全部角色（含已授权权限 ID），按 ID 升序。 */
    public List<AdminRoleView> list() {
        return sysRoleMapper.selectList(Wrappers.<SysRoleEntity>lambdaQuery()
                        .orderByAsc(SysRoleEntity::getId)).stream()
                .map(role -> AdminRoleView.from(role,
                        sysPermissionMapper.selectPermissionIdsByRoleId(role.getId())))
                .toList();
    }

    /** 全部权限节点（授权树数据源）。 */
    public List<PermissionView> listPermissions() {
        return sysPermissionMapper.selectAllNodes().stream().map(PermissionView::from).toList();
    }

    /** 新增角色并授权。 */
    @Transactional
    public Long create(AdminRoleSaveRequest request) {
        String name = requireName(request.name());
        String code = requireCode(request.code());
        ensureCodeAvailable(code, null);
        List<Long> permissionIds = normalizePermissionIds(request.permissionIds());
        validatePermissions(permissionIds);

        SysRoleEntity role = new SysRoleEntity();
        role.setName(name);
        role.setCode(code);
        role.setStatus(normalizeStatus(request.status()));
        try {
            sysRoleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            throw new BizException(ROLE_CODE_EXISTS);
        }
        bindPermissions(role.getId(), permissionIds);
        return role.getId();
    }

    /** 编辑角色并全量覆盖授权（内置超级管理员角色除外）。 */
    @Transactional
    public void update(Long id, AdminRoleSaveRequest request) {
        SysRoleEntity existing = loadRole(id);
        ensureMutable(existing);
        String name = requireName(request.name());
        String code = requireCode(request.code());
        ensureCodeAvailable(code, id);
        List<Long> permissionIds = normalizePermissionIds(request.permissionIds());
        validatePermissions(permissionIds);

        SysRoleEntity update = new SysRoleEntity();
        update.setId(id);
        update.setName(name);
        update.setCode(code);
        update.setStatus(normalizeStatus(request.status()));
        try {
            sysRoleMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BizException(ROLE_CODE_EXISTS);
        }
        bindPermissions(id, permissionIds);
        permissionService.evictPermissionsByRole(id);
    }

    /** 启用（1）/停用（0）角色；停用后相关管理员权限立即失效。 */
    @Transactional
    public void changeStatus(Long id, Integer status) {
        SysRoleEntity role = loadRole(id);
        ensureMutable(role);
        SysRoleEntity update = new SysRoleEntity();
        update.setId(id);
        update.setStatus(normalizeStatus(status));
        sysRoleMapper.updateById(update);
        permissionService.evictPermissionsByRole(id);
    }

    /** 删除角色：已分配给管理员时拒绝，删除前清理角色-权限关联。 */
    @Transactional
    public void delete(Long id) {
        SysRoleEntity role = loadRole(id);
        ensureMutable(role);
        if (sysRoleMapper.countUsersByRoleId(id) > 0) {
            throw new BizException(ROLE_IN_USE);
        }
        sysPermissionMapper.deleteRolePermissions(id);
        sysRoleMapper.deleteById(id);
        permissionService.evictPermissionsByRole(id);
    }

    private SysRoleEntity loadRole(Long id) {
        SysRoleEntity role = sysRoleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ROLE_NOT_FOUND);
        }
        return role;
    }

    private static void ensureMutable(SysRoleEntity role) {
        if (SUPER_ROLE_CODE.equals(role.getCode())) {
            throw new BizException(SUPER_ROLE_PROTECTED);
        }
    }

    private void ensureCodeAvailable(String code, Long excludeId) {
        SysRoleEntity exists = sysRoleMapper.selectOne(
                Wrappers.<SysRoleEntity>lambdaQuery().eq(SysRoleEntity::getCode, code));
        if (exists != null && !Objects.equals(exists.getId(), excludeId)) {
            throw new BizException(ROLE_CODE_EXISTS);
        }
    }

    private static String requireName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 32) {
            throw new BizException("角色名称不能为空且不超过 32 个字符");
        }
        return value;
    }

    private static String requireCode(String code) {
        String value = code == null ? "" : code.trim();
        if (!value.startsWith(ROLE_CODE_PREFIX) || value.length() > 32) {
            throw new BizException("角色标识必须以 ROLE_ 开头且不超过 32 个字符");
        }
        return value;
    }

    private static int normalizeStatus(Integer status) {
        if (status == null) {
            return STATUS_ENABLED;
        }
        if (status != STATUS_ENABLED && status != STATUS_DISABLED) {
            throw new BizException("角色状态不合法");
        }
        return status;
    }

    private static List<Long> normalizePermissionIds(List<Long> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        permissionIds.stream().filter(Objects::nonNull).forEach(distinct::add);
        return List.copyOf(distinct);
    }

    private void validatePermissions(List<Long> permissionIds) {
        for (Long permissionId : permissionIds) {
            SysPermissionEntity permission = sysPermissionMapper.selectById(permissionId);
            if (permission == null) {
                throw new BizException("权限不存在");
            }
        }
    }

    private void bindPermissions(Long roleId, List<Long> permissionIds) {
        sysPermissionMapper.deleteRolePermissions(roleId);
        for (Long permissionId : permissionIds) {
            sysPermissionMapper.insertRolePermission(roleId, permissionId);
        }
    }
}
