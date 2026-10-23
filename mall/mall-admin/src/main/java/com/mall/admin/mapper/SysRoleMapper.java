package com.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.admin.entity.SysRoleEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台角色 Mapper。
 *
 * <p>{@code sys_user_role} 为多对多关联表、无独立实体，因此"某管理员拥有哪些角色"以注解 SQL 直查。</p>
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRoleEntity> {

    /** 查询管理员关联的角色 ID（未关联返回空列表）。 */
    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /** 查询管理员关联的启用中角色（管理端用户列表回显用）。 */
    @Select("SELECT r.id, r.name, r.code, r.status, r.create_time, r.update_time, r.deleted "
            + "FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} AND r.deleted = 0 ORDER BY r.id")
    List<SysRoleEntity> selectRolesByUserId(@Param("userId") Long userId);

    /** 查询拥有某角色的管理员 ID（权限/角色变更后失效缓存用）。 */
    @Select("SELECT user_id FROM sys_user_role WHERE role_id = #{roleId}")
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

    /** 统计某角色已分配的管理员数量（删除角色前校验）。 */
    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_id = #{roleId}")
    long countUsersByRoleId(@Param("roleId") Long roleId);

    /** 绑定管理员与角色。 */
    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 清除某管理员的全部角色关联。 */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Long userId);

    /** 清除某角色的全部管理员关联。 */
    @Delete("DELETE FROM sys_user_role WHERE role_id = #{roleId}")
    int deleteRoleUsers(@Param("roleId") Long roleId);
}
