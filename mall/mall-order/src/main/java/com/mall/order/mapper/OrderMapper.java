package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主订单 Mapper（表 {@code `order`}，保留字由实体 @TableName 反引号处理）。
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {
}
