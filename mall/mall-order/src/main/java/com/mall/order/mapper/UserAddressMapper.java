package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.UserAddressEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址 Mapper。
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddressEntity> {
}
