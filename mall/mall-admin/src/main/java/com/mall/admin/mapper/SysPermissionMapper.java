package com.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.admin.entity.SysPermissionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 平台权限/菜单 Mapper。
 *
 * <p>{@code sys_role_permission} 为多对多关联表、无独立实体，相关查询以注解 SQL 直查；
 * 自定义 SQL 不会被 MyBatis-Plus 自动追加逻辑删除条件，故各语句显式带上 {@code deleted = 0}
 * 与 {@code status = 1}。</p>
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermissionEntity> {

    /** 查询角色集合所拥有的权限 ID（去重；仅统计启用中且未删除的角色）。 */
    @Select("<script>"
            + "SELECT DISTINCT rp.permission_id FROM sys_role_permission rp "
            + "JOIN sys_role r ON r.id = rp.role_id "
            + "WHERE r.deleted = 0 AND r.status = 1 AND rp.role_id IN "
            + "<foreach collection='roleIds' item='roleId' open='(' separator=',' close=')'>#{roleId}</foreach>"
            + "</script>")
    List<Long> selectPermissionIdsByRoleIds(@Param("roleIds") Collection<Long> roleIds);

    /** 按权限 ID 批量取启用中的权限项。 */
    @Select("<script>"
            + "SELECT id, parent_id, name, code, type, path, sort, status FROM sys_permission "
            + "WHERE deleted = 0 AND status = 1 AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<SysPermissionEntity> selectEnabledByIds(@Param("ids") Collection<Long> ids);

    /** 按权限码批量取菜单节点（仅 MENU，按 sort 升序）。 */
    @Select("<script>"
            + "SELECT id, parent_id, name, code, type, path, sort, status FROM sys_permission "
            + "WHERE deleted = 0 AND status = 1 AND type = 'MENU' AND code IN "
            + "<foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach>"
            + " ORDER BY sort"
            + "</script>")
    List<SysPermissionEntity> selectMenusByCodes(@Param("codes") Collection<String> codes);

    /** 全部未删除权限节点（角色授权树用，含停用项以便管理员识别）。 */
    @Select("SELECT id, parent_id, name, code, type, path, sort, status FROM sys_permission "
            + "WHERE deleted = 0 ORDER BY sort, id")
    List<SysPermissionEntity> selectAllNodes();

    /** 查询角色已授权的权限 ID。 */
    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);

    /** 清除角色全部权限关联。 */
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteRolePermissions(@Param("roleId") Long roleId);

    /** 绑定角色与权限。 */
    @Insert("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (#{roleId}, #{permissionId})")
    int insertRolePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);
}
