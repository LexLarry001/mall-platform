-- =============================================================================
-- M4 支付域数据库设计（交付物汇总）
-- 里程碑：M4 支付宝沙箱支付与回调
-- 变更：新增 payment_record 表（与主订单 1:1）；无既有表结构变更；无新增 Redis Key
-- 应用方式：mysql -uroot -p123456 -e "source docker/mysql/init/07-payment-schema.sql"
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- payment_record：支付流水
--   1. 与主订单 1:1（一单一支付，不支持多次部分支付），order_no 唯一键天然幂等。
--   2. out_trade_no（提交给支付宝的商户单号）即主订单号，不单独建列。
--   3. 支付行为事实快照，不做逻辑删除；notify_raw 仅保留最近一次回调报文。
--   4. 写入时机：POST /api/user/pay/{orderNo} 发起支付时落库（金额=order.pay_amount 快照）；
--      回填时机：markPaid 内核（异步回调 / 主动查询共用）置 status=1 并回填
--      trade_no / notify_raw / pay_time。
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- 状态机推进（M4 新增流转，无 DDL 变更，仅语义说明）
--   主订单: 0待支付 ──markPaid(notify/query 共用语义)──> 1已支付
--   子订单: 0待支付 ──markPaid──> 1待发货
--   守卫：仅 0→1 条件更新生效（幂等）；已终态订单的成功回调/查询仅记日志忽略（迟到支付）。
--   Redis：支付成功仅 ZREM mall:order:timeout 超时锚点，无新增 Key。
-- -----------------------------------------------------------------------------
