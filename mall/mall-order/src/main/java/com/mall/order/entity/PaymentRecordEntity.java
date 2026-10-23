package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水（表 {@code payment_record}，与主订单 1:1，{@code order_no} 唯一键天然幂等）。
 *
 * <p>status：0 待支付 / 1 支付成功；trade_no / pay_time 在支付成功后回填；
 * notify_raw 仅保留最近一次异步回调原始报文（排查依据）。</p>
 */
@TableName("payment_record")
public class PaymentRecordEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主订单号（= out_trade_no）。 */
    private String orderNo;

    /** 支付宝交易号（支付成功后回填）。 */
    private String tradeNo;

    /** 支付金额（发起支付时快照 = order.pay_amount）。 */
    private BigDecimal amount;

    /** 0 待支付 / 1 支付成功。 */
    private Integer status;

    /** 最近一次异步回调原始报文。 */
    private String notifyRaw;

    /** 支付完成时间（回调 gmt_payment / 查询 send_pay_date）。 */
    private LocalDateTime payTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getNotifyRaw() {
        return notifyRaw;
    }

    public void setNotifyRaw(String notifyRaw) {
        this.notifyRaw = notifyRaw;
    }

    public LocalDateTime getPayTime() {
        return payTime;
    }

    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
