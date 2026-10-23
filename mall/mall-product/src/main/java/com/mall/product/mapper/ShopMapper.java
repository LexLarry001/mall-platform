package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.product.entity.ShopEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 店铺 Mapper。{@code merchant_id} 唯一（入驻审核通过才建店）。
 */
@Mapper
public interface ShopMapper extends BaseMapper<ShopEntity> {

    /** 按商家账号查店铺，未开店返回 {@code null}。 */
    default ShopEntity selectByMerchantId(Long merchantId) {
        return selectOne(Wrappers.<ShopEntity>lambdaQuery().eq(ShopEntity::getMerchantId, merchantId));
    }
}
