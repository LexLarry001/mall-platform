package com.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发货单（表 {@code shipment}，子订单级 1:1，{@code sub_order_id} 唯一键天然幂等）。
 *
 * <p>物流中间状态全部在此表；子订单 status 是交易视角（0待支付/1待发货/2待收货/3已完成/4已取消），
 * 与物流视角解耦。provider / 经纬度字段为高德可视化预留，本期为 null。</p>
 */
@TableName("shipment")
public class ShipmentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 发货单号：SH+雪花。 */
    private String shipmentNo;

    private Long subOrderId;

    private String subOrderNo;

    /** manual 手动 / amap 高德接入时切换。 */
    private String provider;

    /** 承运商编码（YTO/ZTO/STO/YUNDA/EMS）。 */
    private String carrierCode;

    /** 承运商展示名（圆通/中通/申通/韵达/EMS）。 */
    private String carrierName;

    private String trackingNo;

    /** 发货地址（商家填写，M7 高德地理编码源）。 */
    private String fromAddress;

    /** 收货地址（主订单快照，M7 高德地理编码源）。 */
    private String toAddress;

    /** SHIPPED / IN_TRANSIT / OUT_FOR_DELIVERY / DELIVERED / EXCEPTION。 */
    private String currentStatus;

    /** 轨迹节点数（缓存 MongoDB 文档数，方便列表展示）。 */
    private Integer traceCount;

    /** 发货地经度（预留高德接入）。 */
    private BigDecimal fromLng;
    private BigDecimal fromLat;

    /** 收货地经度（预留）。 */
    private BigDecimal toLng;
    private BigDecimal toLat;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShipmentNo() {
        return shipmentNo;
    }

    public void setShipmentNo(String shipmentNo) {
        this.shipmentNo = shipmentNo;
    }

    public Long getSubOrderId() {
        return subOrderId;
    }

    public void setSubOrderId(Long subOrderId) {
        this.subOrderId = subOrderId;
    }

    public String getSubOrderNo() {
        return subOrderNo;
    }

    public void setSubOrderNo(String subOrderNo) {
        this.subOrderNo = subOrderNo;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public void setCarrierCode(String carrierCode) {
        this.carrierCode = carrierCode;
    }

    public String getCarrierName() {
        return carrierName;
    }

    public void setCarrierName(String carrierName) {
        this.carrierName = carrierName;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getToAddress() {
        return toAddress;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }

    public Integer getTraceCount() {
        return traceCount;
    }

    public void setTraceCount(Integer traceCount) {
        this.traceCount = traceCount;
    }

    public BigDecimal getFromLng() {
        return fromLng;
    }

    public void setFromLng(BigDecimal fromLng) {
        this.fromLng = fromLng;
    }

    public BigDecimal getFromLat() {
        return fromLat;
    }

    public void setFromLat(BigDecimal fromLat) {
        this.fromLat = fromLat;
    }

    public BigDecimal getToLng() {
        return toLng;
    }

    public void setToLng(BigDecimal toLng) {
        this.toLng = toLng;
    }

    public BigDecimal getToLat() {
        return toLat;
    }

    public void setToLat(BigDecimal toLat) {
        this.toLat = toLat;
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
