package com.mall.order.logistics;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物流轨迹节点（MongoDB 集合 {@code logistics_trace}）。
 *
 * <p>时序追加写、写多读少、节点结构随数据源变化（手动模式只有文本，高德模式带经纬度与折线），
 * 典型文档式数据（规格 §5.7 / §6.2）。索引 {@code {shipment_no: 1, ts: -1}} 支持时间倒序查。</p>
 *
 * <p>未来接高德后：商家发货时对收货地址地理编码得到 {@code to_lng/to_lat}，
 * 路径规划得到 {@code polyline}（坐标数组），{@code POSITION} 类型节点写入实时经纬度。</p>
 */
@Document("logistics_trace")
public class LogisticsTraceDoc {

    /**
     * 节点类型：STATUS（状态变更）/ ROUTE（M7 高德路线，发货时写一次，带折线）/
     * POSITION（实时位置，预留）。
     */
    public static final String NODE_STATUS = "STATUS";
    public static final String NODE_ROUTE = "ROUTE";
    public static final String NODE_POSITION = "POSITION";

    @Id
    private String id;

    /** 发货单号（MySQL shipment.shipment_no）。 */
    @Indexed
    private String shipmentNo;

    /** STATUS / ROUTE / POSITION。 */
    private String nodeType;

    /** 当前物流状态常量（STATUS 节点：SHIPPED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION）。 */
    private String status;

    /** 人类可读的中文描述："包裹已交给圆通快递"。 */
    private String desc;

    /** 可选：触发该节点的位置描述，如"上海浦东分拣中心"。 */
    private String address;

    /** 可选：高德接入后，经纬度。 */
    private BigDecimal lng;
    private BigDecimal lat;

    /** 可选：高德接入后，配送路径折线（坐标数组）。 */
    private java.util.List<double[]> polyline;

    /** 节点时间。 */
    @Indexed
    private LocalDateTime ts;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getShipmentNo() {
        return shipmentNo;
    }

    public void setShipmentNo(String shipmentNo) {
        this.shipmentNo = shipmentNo;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public void setLng(BigDecimal lng) {
        this.lng = lng;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public void setLat(BigDecimal lat) {
        this.lat = lat;
    }

    public java.util.List<double[]> getPolyline() {
        return polyline;
    }

    public void setPolyline(java.util.List<double[]> polyline) {
        this.polyline = polyline;
    }

    public LocalDateTime getTs() {
        return ts;
    }

    public void setTs(LocalDateTime ts) {
        this.ts = ts;
    }
}
