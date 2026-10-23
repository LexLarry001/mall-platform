package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主订单（表 {@code `order`}，MySQL 保留字必须反引号）。
 *
 * <p>status 状态机（M3 部分）：0 待支付 ──取消/超时──&gt; 3 已取消；
 * 1 已支付 / 2 已完成 由 M4/M5 推进。receiver/phone/full_address 为下单时地址快照。</p>
 */
@TableName("`order`")
public class OrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主订单号：M+雪花，字符串。 */
    private String orderNo;

    private Long userId;

    private BigDecimal totalAmount;

    /** 实付金额（M3 等于总额，M4 起含优惠）。 */
    private BigDecimal payAmount;

    /** 0 待支付/1 已支付/2 已完成/3 已取消。 */
    private Integer status;

    private String receiver;

    private String phone;

    /** 完整收货地址快照：省市区+详细。 */
    private String fullAddress;

    /** 支付截止时间（创建 +30 分钟，超时关单依据）。 */
    private LocalDateTime expireTime;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(BigDecimal payAmount) {
        this.payAmount = payAmount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getFullAddress() {
        return fullAddress;
    }

    public void setFullAddress(String fullAddress) {
        this.fullAddress = fullAddress;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
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
