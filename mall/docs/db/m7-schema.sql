-- M7 高德地图物流可视化：发货单补地址列
-- 说明：`from_lng/from_lat/to_lng/to_lat` 在 M5 已预留，本期直接启用。
-- 执行方式（存量库）：mysql -uroot -p123456 mall < mall/docs/db/m7-schema.sql
-- 幂等提示：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行前先查 information_schema。

ALTER TABLE `shipment`
    ADD COLUMN `from_address` VARCHAR(255) NULL COMMENT '发货地址（商家填写，高德地理编码源）' AFTER `tracking_no`,
    ADD COLUMN `to_address` VARCHAR(500) NULL COMMENT '收货地址（主订单快照，高德地理编码源）' AFTER `from_address`;

-- 验证
-- SELECT shipment_no, from_address, to_address, from_lng, from_lat, to_lng, to_lat
-- FROM shipment ORDER BY create_time DESC LIMIT 5;
