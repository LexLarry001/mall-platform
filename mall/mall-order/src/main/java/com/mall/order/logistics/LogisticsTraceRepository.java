package com.mall.order.logistics;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * 物流轨迹 Repository（spring-data-mongo 自动实现）。
 * 方法命名规则派生：findByShipmentNoOrderByTsDesc 即 SELECT * WHERE shipment_no=? ORDER BY ts DESC。
 */
public interface LogisticsTraceRepository extends MongoRepository<LogisticsTraceDoc, String> {

    /** 按发货单号查全部轨迹节点，时间倒序（最新在前）。 */
    List<LogisticsTraceDoc> findByShipmentNoOrderByTsDesc(String shipmentNo);
}
