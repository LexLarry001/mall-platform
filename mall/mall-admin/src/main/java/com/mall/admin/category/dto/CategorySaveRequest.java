package com.mall.admin.category.dto;

/**
 * 类目新增/编辑请求体。
 *
 * @param parentId 父类目 ID，顶级传 0 或空
 */
public record CategorySaveRequest(Long parentId, String name, Integer sort) {
}
