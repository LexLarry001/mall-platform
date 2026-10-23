package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.entity.RefundOrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 退款单 Mapper（{@code refund_no} 唯一，同时是支付宝 out_request_no 幂等键）。
 */
@Mapper
public interface RefundOrderMapper extends BaseMapper<RefundOrderEntity> {
}
