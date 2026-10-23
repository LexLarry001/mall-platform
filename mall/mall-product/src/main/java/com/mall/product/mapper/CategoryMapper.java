package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.CategoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台类目 Mapper（parent_id 树）。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<CategoryEntity> {
}
