package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 子订单（表 {@code sub_order}）：按店铺拆单，一店一单。
 *
 * <p>status 状态机（M3 部分）：0 待支付 ──取消/超时──&gt; 4 已取消；
 * 1 待发货 / 2 待收货 / 3 已完成 由 M4/M5 推进。shop_name 为快照。</p>
 */
@TableName("sub_order")
public class SubOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 子订单号：S+雪花，字符串。 */
    private String subOrderNo;

    private Long orderId;

    private Long shopId;

    private String shopName;

    /** 子订单金额（本店商品金额合计）。 */
    private BigDecimal amount;

    /** 0 待支付/1 待发货/2 待收货/3 已完成/4 已取消。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSubOrderNo() {
        return subOrderNo;
    }

    public void setSubOrderNo(String subOrderNo) {
        this.subOrderNo = subOrderNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
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
