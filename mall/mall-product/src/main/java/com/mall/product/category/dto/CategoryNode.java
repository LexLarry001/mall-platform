package com.mall.product.category.dto;

import com.mall.product.entity.CategoryEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 类目树节点（买家导航与平台管理共用）。
 *
 * <p>{@code children} 为可变列表，组树时挂载子节点；Jackson 序列化用于
 * {@code mall:category:tree} 缓存读写。</p>
 */
public record CategoryNode(Long id, Long parentId, String name, Integer sort, Integer status,
                           List<CategoryNode> children) {

    /** 由表记录映射，children 初始为空列表待组树填充。 */
    public static CategoryNode from(CategoryEntity entity) {
        return new CategoryNode(entity.getId(), entity.getParentId(), entity.getName(),
                entity.getSort(), entity.getStatus(), new ArrayList<>());
    }
}
