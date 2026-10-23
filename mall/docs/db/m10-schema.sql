-- =============================================================================
-- M10 评价体系建表脚本：商品评价 + 商品评价版本号 + 平台评价审核权限
-- 说明：
--   1. 与交付物 mall/docs/db/m10-schema.sql 保持一致。
--   2. 本脚本可重复执行：表用 CREATE TABLE IF NOT EXISTS，列用 information_schema 判断。
--   3. 权限 ID 沿用现有约定，M1 占用 1-8、M2 占用 10-28，本文件使用 29-31。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 商品评价表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_review` (
    `id`             BIGINT        NOT NULL COMMENT '评价 ID（雪花，应用侧 ASSIGN_ID）',
    `user_id`        BIGINT        NOT NULL COMMENT '买家账号 ID（user.id）',
    `user_name`      VARCHAR(32)   NOT NULL COMMENT '买家用户名快照（用于展示与匿名脱敏）',
    `order_id`       BIGINT        NOT NULL COMMENT '主订单 ID（快照，便于追溯）',
    `sub_order_id`   BIGINT        NOT NULL COMMENT '子订单 ID',
    `order_item_id`  BIGINT        NOT NULL COMMENT '订单项 ID（唯一键，防重复评价）',
    `spu_id`         BIGINT        NOT NULL COMMENT '商品 SPU ID',
    `sku_id`         BIGINT        NOT NULL COMMENT 'SKU ID',
    `shop_id`        BIGINT        NOT NULL COMMENT '店铺 ID',
    `shop_name`      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '店铺名称快照',
    `product_name`   VARCHAR(64)   NOT NULL COMMENT '商品名称快照',
    `main_img`       VARCHAR(255)  NOT NULL COMMENT '商品主图快照',
    `rating`         TINYINT       NOT NULL COMMENT '评分 1~5',
    `content`        VARCHAR(1000) NOT NULL COMMENT '评价正文 5~500 字',
    `images`         VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '评价图 URL，逗号分隔，最多 6 张',
    `anonymous`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0 实名 / 1 匿名',
    `tags`           VARCHAR(500)  NOT NULL DEFAULT '' COMMENT 'AI 标签 JSON 数组',
    `sentiment`      TINYINT       NULL COMMENT '1 正面 / 0 中性 / 2 负面；NULL 未分析',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '1 展示 / 2 平台隐藏',
    `hide_reason`    VARCHAR(200)  NULL COMMENT '平台隐藏原因',
    `merchant_reply` VARCHAR(500)  NULL COMMENT '商家回复（一次性）',
    `reply_time`     DATETIME      NULL COMMENT '回复时间',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_item` (`order_item_id`),
    KEY `idx_review_spu_status_time` (`spu_id`, `status`, `create_time`),
    KEY `idx_review_user_time` (`user_id`, `create_time`),
    KEY `idx_review_shop_status_time` (`shop_id`, `status`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='商品评价';

-- -----------------------------------------------------------------------------
-- 2. 商品评价版本号（摘要缓存失效依据）
-- -----------------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product'
                     AND COLUMN_NAME = 'review_version');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `product` ADD COLUMN `review_version` INT NOT NULL DEFAULT 0 COMMENT ''评价版本号：新增/隐藏评价时 +1''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3. 平台端评价审核菜单与权限（绑定 ROLE_SUPER）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `code`, `type`, `path`, `sort`, `status`)
VALUES (29, 0, '评价管理', 'review', 'MENU', '/review', 70, 1),
       (30, 29, '评价审核', 'review:audit:list', 'MENU', '/review/audit', 71, 1),
       (31, 30, '隐藏/恢复', 'review:audit:handle', 'BUTTON', NULL, 1, 1)
ON DUPLICATE KEY UPDATE `parent_id` = VALUES(`parent_id`), `name` = VALUES(`name`),
                        `type` = VALUES(`type`), `path` = VALUES(`path`),
                        `sort` = VALUES(`sort`), `status` = VALUES(`status`);

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
VALUES (1, 29), (1, 30), (1, 31)
ON DUPLICATE KEY UPDATE `permission_id` = VALUES(`permission_id`);
