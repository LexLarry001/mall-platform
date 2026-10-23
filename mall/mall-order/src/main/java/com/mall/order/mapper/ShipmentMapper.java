package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.ShipmentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 发货单 Mapper（{@code sub_order_id} 唯一键，商家重复发货冲突即返回业务错误）。
 */
@Mapper
public interface ShipmentMapper extends BaseMapper<ShipmentEntity> {
}
