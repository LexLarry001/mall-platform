package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.OrderItemEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单项 Mapper。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {
}
