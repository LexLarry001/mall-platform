package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.SubOrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 子订单 Mapper。
 */
@Mapper
public interface SubOrderMapper extends BaseMapper<SubOrderEntity> {
}
