package com.mall.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.merchant.entity.MerchantEntity;
import org.apache.ibatis.annotations.Mapper;

/** 商家账号 Mapper。 */
@Mapper
public interface MerchantMapper extends BaseMapper<MerchantEntity> {

    /** 按用户名查询未删除的商家账号，不存在返回 {@code null}。 */
    default MerchantEntity selectByUsername(String username) {
        return selectOne(Wrappers.<MerchantEntity>lambdaQuery().eq(MerchantEntity::getUsername, username));
    }
}
