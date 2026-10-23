package com.mall.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 平台权限/菜单项（表 {@code sys_permission}）。
 *
 * <p>一张表同时承载"可授权的最小单元"与"管理端菜单树"：{@code type=MENU} 的节点既是权限码
 * 也是侧边栏菜单项，{@code type=BUTTON} 的节点只作细粒度授权（不出现在菜单里）。</p>
 */
@TableName("sys_permission")
public class SysPermissionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 父节点 ID，顶级为 0。 */
    private Long parentId;

    private String name;

    /** 权限码（同名 also 作为菜单标识），全局唯一。 */
    private String code;

    /** MENU / BUTTON。 */
    private String type;

    /** 前端路由路径，仅 MENU 有值。 */
    private String path;

    private Integer sort;

    /** 1 启用，0 停用。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
