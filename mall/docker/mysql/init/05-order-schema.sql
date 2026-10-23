-- =============================================================================
-- M3 交易域 DDL：user_address / `order` / sub_order / order_item
-- 说明：
--   1. 与交付物 mall/docs/db/m3-schema.sql 保持一致，两者任一改动须同步另一方。
--   2. `order` 为 MySQL 保留字，DDL、@TableName、XML 一律反引号。
--   3. 订单行为事实快照，不做逻辑删除（无 deleted 列）；金额一律 DECIMAL(10,2)。
--   4. 主键雪花 ID（应用侧 ASSIGN_ID，无 AUTO_INCREMENT），SQL 直插须显式 id。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 买家收货地址（同一买家仅一条 is_default=1，由应用事务保证互斥）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_address` (
    `id`          BIGINT      NOT NULL COMMENT '地址 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `user_id`     BIGINT      NOT NULL COMMENT '所属买家账号 ID（user.id）',
    `receiver`    VARCHAR(32) NOT NULL COMMENT '收货人姓名',
    `phone`       VARCHAR(20) NOT NULL COMMENT '收货人手机号',
    `province`    VARCHAR(32) NOT NULL COMMENT '省',
    `city`        VARCHAR(32) NOT NULL COMMENT '市',
    `district`    VARCHAR(32) NOT NULL COMMENT '区/县',
    `detail`      VARCHAR(255) NOT NULL COMMENT '详细地址（街道门牌）',
    `is_default`  TINYINT     NOT NULL DEFAULT 0 COMMENT '是否默认地址：1 是，0 否',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_address_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '买家收货地址';

-- -----------------------------------------------------------------------------
-- 2. 主订单（`order` 为保留字）
--   status 状态机（M3 部分）：0 待支付 ──取消/超时──> 3 已取消；
--   1 已支付 / 2 已完成 由 M4 支付、M5 收货推进。
--   receiver/phone/full_address 为下单时的地址快照，防地址删改影响历史订单。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order` (
    `id`            BIGINT         NOT NULL COMMENT '主订单 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `order_no`      VARCHAR(32)    NOT NULL COMMENT '主订单号：M+雪花，字符串',
    `user_id`       BIGINT         NOT NULL COMMENT '买家账号 ID（user.id）',
    `total_amount`  DECIMAL(10, 2) NOT NULL COMMENT '订单总额（商品金额合计，运费固定 0）',
    `pay_amount`    DECIMAL(10, 2) NOT NULL COMMENT '实付金额（M3 等于总额，M4 起含优惠）',
    `status`        TINYINT        NOT NULL DEFAULT 0 COMMENT '0 待支付/1 已支付/2 已完成/3 已取消',
    `receiver`      VARCHAR(32)    NOT NULL COMMENT '收货人姓名（快照）',
    `phone`         VARCHAR(20)    NOT NULL COMMENT '收货人手机号（快照）',
    `full_address`  VARCHAR(500)   NOT NULL COMMENT '完整收货地址（快照：省市区+详细）',
    `expire_time`   DATETIME       NOT NULL COMMENT '支付截止时间（创建 +30 分钟，超时关单依据）',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_order_user_status` (`user_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '主订单';

-- -----------------------------------------------------------------------------
-- 3. 子订单（按 shop 拆单，一店一单）
--   status 状态机（M3 部分）：0 待支付 ──取消/超时──> 4 已取消；
--   1 待发货 / 2 待收货 / 3 已完成 由 M4 支付回调、M5 发货收货推进。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sub_order` (
    `id`            BIGINT         NOT NULL COMMENT '子订单 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `sub_order_no`  VARCHAR(32)    NOT NULL COMMENT '子订单号：S+雪花，字符串',
    `order_id`      BIGINT         NOT NULL COMMENT '所属主订单 ID（order.id）',
    `shop_id`       BIGINT         NOT NULL COMMENT '店铺 ID',
    `shop_name`     VARCHAR(64)    NOT NULL COMMENT '店铺名称（快照）',
    `amount`        DECIMAL(10, 2) NOT NULL COMMENT '子订单金额（本店商品金额合计）',
    `status`        TINYINT        NOT NULL DEFAULT 0 COMMENT '0 待支付/1 待发货/2 待收货/3 已完成/4 已取消',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sub_order_no` (`sub_order_no`),
    KEY `idx_sub_order_order` (`order_id`),
    KEY `idx_sub_order_shop_status` (`shop_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '子订单';

-- -----------------------------------------------------------------------------
-- 4. 订单项（商品快照：名称/主图/规格/单价，防后续改价改图影响历史订单）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_item` (
    `id`           BIGINT         NOT NULL COMMENT '订单项 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `sub_order_id` BIGINT         NOT NULL COMMENT '所属子订单 ID（sub_order.id）',
    `spu_id`       BIGINT         NOT NULL COMMENT 'SPU ID',
    `sku_id`       BIGINT         NOT NULL COMMENT 'SKU ID',
    `product_name` VARCHAR(64)    NOT NULL COMMENT '商品名称（快照）',
    `main_img`     VARCHAR(255)   NOT NULL COMMENT '主图 URL（快照）',
    `spec_json`    VARCHAR(500)   NOT NULL COMMENT '规格值组合 JSON（快照）',
    `price`        DECIMAL(10, 2) NOT NULL COMMENT '成交单价（快照）',
    `quantity`     INT            NOT NULL COMMENT '购买数量',
    `create_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_item_sub` (`sub_order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '订单项';
