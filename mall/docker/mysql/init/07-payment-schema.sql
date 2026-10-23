-- =============================================================================
-- M4 支付域：支付流水表
-- 说明：
--   1. 与主订单 1:1（一单一支付，不支持多次部分支付），order_no 唯一键天然幂等。
--   2. out_trade_no（提交给支付宝的商户单号）即主订单号，不单独建列。
--   3. 支付行为事实快照，不做逻辑删除；notify_raw 仅保留最近一次回调报文。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `payment_record` (
    `id`          BIGINT         NOT NULL COMMENT '支付流水 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `order_no`    VARCHAR(32)    NOT NULL COMMENT '主订单号（= out_trade_no，与主订单 1:1）',
    `trade_no`    VARCHAR(64)    NULL COMMENT '支付宝交易号（支付成功后回填）',
    `amount`      DECIMAL(10, 2) NOT NULL COMMENT '支付金额（发起支付时快照 = order.pay_amount）',
    `status`      TINYINT        NOT NULL DEFAULT 0 COMMENT '0 待支付/1 支付成功',
    `notify_raw`  TEXT           NULL COMMENT '最近一次异步回调原始报文（排查依据）',
    `pay_time`    DATETIME       NULL COMMENT '支付完成时间（回调 gmt_payment / 查询 send_pay_date）',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_order_no` (`order_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '支付流水';
