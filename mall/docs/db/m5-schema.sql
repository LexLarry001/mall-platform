-- =============================================================================
-- M5 履约与物流数据库设计（MySQL shipment + MongoDB logistics_trace）
-- 说明：
--   1. 与 docker/mysql/init/08-shipment-schema.sql 保持一致，任一改动须同步另一方。
--   2. 交易状态与物流状态解耦：子订单 status 只表达交易视角，物流中间状态在
--      shipment.current_status 与 MongoDB 轨迹节点中。
--   3. provider / 经纬度字段为高德可视化预留，本期恒为 manual / NULL。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 发货单（子订单级 1:1，uk_sub_order 天然幂等）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `shipment` (
  `id`             BIGINT       NOT NULL COMMENT '雪花 ID',
  `shipment_no`    VARCHAR(32)  NOT NULL COMMENT '发货单号（SH+雪花）',
  `sub_order_id`   BIGINT       NOT NULL COMMENT '子订单 ID（唯一，1:1 发货）',
  `sub_order_no`   VARCHAR(32)  NOT NULL COMMENT '子订单号（冗余方便查）',
  `provider`       VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT '物流提供商：manual | amap（预留）',
  `carrier_code`   VARCHAR(32)  NOT NULL COMMENT '承运商编码：YTO/ZTO/STO/YUNDA/EMS 等',
  `carrier_name`   VARCHAR(32)  NOT NULL COMMENT '承运商展示名：圆通/中通/申通/韵达/EMS',
  `tracking_no`    VARCHAR(64)  NOT NULL COMMENT '运单号',
  `current_status` VARCHAR(16)  NOT NULL DEFAULT 'SHIPPED' COMMENT '当前物流状态：SHIPPED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION',
  `trace_count`    INT          NOT NULL DEFAULT 1 COMMENT '轨迹节点数（缓存）',
  `from_lng`       DECIMAL(10,6) NULL COMMENT '发货地经度（预留高德接入）',
  `from_lat`       DECIMAL(10,6) NULL COMMENT '发货地纬度（预留）',
  `to_lng`         DECIMAL(10,6) NULL COMMENT '收货地经度（预留）',
  `to_lat`         DECIMAL(10,6) NULL COMMENT '收货地纬度（预留）',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shipment_no` (`shipment_no`),
  UNIQUE KEY `uk_sub_order` (`sub_order_id`),
  KEY `idx_sub_order_no` (`sub_order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货单（子订单级 1:1，物流提供商可插拔）';

-- -----------------------------------------------------------------------------
-- 2. MongoDB 集合：logistics_trace（轨迹节点，时序追加写）
--    由 Spring Data MongoDB 自动创建，@Document("logistics_trace")
-- -----------------------------------------------------------------------------
/*
集合文档结构（camelCase 为 Java 字段，Spring Data 默认映射；下例为等价概念模型）：

{
  "_id":        ObjectId,
  "shipmentNo": "SH1958...",          // 发货单号（对应 shipment.shipment_no）
  "nodeType":   "STATUS",              // STATUS 状态节点 | POSITION 实时位置（高德预留）
  "status":     "SHIPPED",             // SHIPPED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION
  "desc":       "包裹已交给圆通，运单号 YTO123456789",
  "address":    null,                  // 可选，位置描述（如"上海浦东分拣中心"）
  "lng":        null,                  // 预留：经度
  "lat":        null,                  // 预留：纬度
  "polyline":   null,                  // 预留：路径折线（[[lng,lat], ...]）
  "ts":         ISODate("2026-10-21T08:00:00Z")
}

索引：
  db.logistics_trace.createIndex({ shipmentNo: 1, ts: -1 });
  db.logistics_trace.createIndex({ ts: 1 });

写入策略：
  - 商家发货：追加 1 条 STATUS:SHIPPED，shipment.trace_count = 1
  - 商家推进：追加 1 条对应状态节点，shipment.trace_count + 1
  - 查询：按 ts 倒序返回（最新在前）
*/

-- -----------------------------------------------------------------------------
-- 3. 状态机（M5 新增流转）
-- -----------------------------------------------------------------------------
/*
子订单：1 待发货 ──商家发货──> 2 待收货 ──买家确认收货──> 3 已完成
        （仅 1→2 / 2→3 条件更新生效，越权/终态统一拒绝）

主订单：1 已支付 ──全部子订单 status ∈ {3,4}──> 2 已完成
        （仅在子订单状态变更后触发聚合检查；发货不移主订单状态）

shipment：SHIPPED ──> IN_TRANSIT ──> OUT_FOR_DELIVERY ──> DELIVERED
          （DELIVERED 为终态，禁止继续推进）
*/
