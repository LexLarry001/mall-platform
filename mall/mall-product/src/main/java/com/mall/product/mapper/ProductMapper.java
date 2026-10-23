package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.ProductEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 SPU Mapper。
 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
}
