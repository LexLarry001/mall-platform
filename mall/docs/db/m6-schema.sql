-- =============================================================================
-- M6 退款售后数据库设计（refund_order 退款单 + 状态机扩展说明）
-- 与 docker/mysql/init/09-refund-schema.sql 保持一致，任一改动须同步另一方。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. refund_order：退款单（订单项粒度，refund_no = 支付宝 out_request_no）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `refund_order` (
    `id`                BIGINT         NOT NULL COMMENT '退款单 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `refund_no`         VARCHAR(32)    NOT NULL COMMENT '退款单号：RF+雪花（= 支付宝 out_request_no，唯一幂等键）',
    `order_id`          BIGINT         NOT NULL COMMENT '主订单 ID（order.id）',
    `order_no`          VARCHAR(32)    NOT NULL COMMENT '主订单号（冗余，支付退款按 out_trade_no 定位）',
    `sub_order_id`      BIGINT         NOT NULL COMMENT '子订单 ID（sub_order.id）',
    `sub_order_no`      VARCHAR(32)    NOT NULL COMMENT '子订单号（冗余）',
    `order_item_id`     BIGINT         NOT NULL COMMENT '订单项 ID（order_item.id，退款粒度）',
    `spu_id`            BIGINT         NOT NULL COMMENT 'SPU ID（快照）',
    `sku_id`            BIGINT         NOT NULL COMMENT 'SKU ID（库存回补依据）',
    `product_name`      VARCHAR(64)    NOT NULL COMMENT '商品名称（快照）',
    `shop_id`           BIGINT         NOT NULL COMMENT '店铺 ID（商家权限守卫依据）',
    `user_id`           BIGINT         NOT NULL COMMENT '买家账号 ID（order.user_id 冗余）',
    `quantity`          INT            NOT NULL COMMENT '退款数量（本期等于订单项数量，整项退）',
    `refund_amount`     DECIMAL(10, 2) NOT NULL COMMENT '退款金额（= 订单项快照单价 × 数量）',
    `reason`            VARCHAR(255)   NOT NULL COMMENT '退款原因（买家填写）',
    `evidence_images`   TEXT           NULL COMMENT '凭证图 URL 数组 JSON（最多 3 张，可空）',
    `status`            TINYINT        NOT NULL DEFAULT 0 COMMENT '0 待审核/2 已退款/3 已驳回/4 退款失败',
    `sub_status_before` TINYINT        NOT NULL COMMENT '申请前子订单状态：1 待发货/2 待收货/7 部分退款',
    `audit_remark`      VARCHAR(255)   NULL COMMENT '商家审核备注 / 驳回理由',
    `audit_time`        DATETIME       NULL COMMENT '审核时间',
    `alipay_trade_no`   VARCHAR(64)    NULL COMMENT '支付宝交易号（退款成功回填）',
    `refund_time`       DATETIME       NULL COMMENT '退款成功时间',
    `fail_code`         VARCHAR(64)    NULL COMMENT '沙箱退款失败错误码（如 ACQ.SYSTEM_ERROR）',
    `fail_msg`          VARCHAR(255)   NULL COMMENT '沙箱退款失败描述',
    `create_time`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    KEY `idx_refund_item` (`order_item_id`, `status`),
    KEY `idx_refund_sub` (`sub_order_id`),
    KEY `idx_refund_shop_status` (`shop_id`, `status`),
    KEY `idx_refund_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '退款单（订单项粒度，out_request_no 幂等）';

-- -----------------------------------------------------------------------------
-- 2. 既有表状态枚举扩展（仅注释变更，无 DDL 变更；status 为 TINYINT 无约束）
-- -----------------------------------------------------------------------------
-- sub_order.status：
--   0 待支付 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消
--   + 5 退款中（存在待审核/处理中的退款）
--   + 6 已退款（全部订单项退款成功，终态）
--   + 7 部分退款（部分订单项退款成功，剩余商品仍可发货/确认收货）
--
-- `order`.status：
--   0 待支付 / 1 已支付 / 2 已完成 / 3 已取消
--   + 4 已退款（全部子订单退款成功）
--
-- 主订单聚合：全部子订单 ∈ {3,4,6} 时推进——全为 6 → 4 已退款，否则 → 2 已完成。

-- -----------------------------------------------------------------------------
-- 3. Redis / MongoDB
-- -----------------------------------------------------------------------------
-- 无新增 Redis Key；无新增 MongoDB 集合（退款凭证为少量图片 URL，随退款单存 MySQL，
-- 保证与退款状态强一致；MongoDB 仍只承载物流轨迹 logistics_trace）。
