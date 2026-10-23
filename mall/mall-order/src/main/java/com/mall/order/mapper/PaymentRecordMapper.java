package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.PaymentRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付流水 Mapper（{@code order_no} 唯一键，重试/并发插入冲突即复用）。
 */
@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecordEntity> {
}
