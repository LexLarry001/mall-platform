package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.entity.ProductSkuEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 SKU Mapper。
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSkuEntity> {

    /**
     * 条件扣减库存：{@code stock >= quantity} 才更新，返回受影响行数。
     * 行数 0 即超卖（库存不足），由调用方回滚事务（plan Task 3 语义）。
     */
    @Update("UPDATE product_sku SET stock = stock - #{quantity}"
            + " WHERE id = #{skuId} AND stock >= #{quantity} AND deleted = 0")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /** 回补库存（取消/超时关单时恢复，M3 取消语义与 Task 4 共用）。 */
    @Update("UPDATE product_sku SET stock = stock + #{quantity} WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);
}
