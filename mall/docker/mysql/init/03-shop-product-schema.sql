-- =============================================================================
-- M2 商品域 DDL：merchant 入驻申请扩展字段 + shop / category / product(SPU) /
--               product_sku / product_spec / file_record
-- 说明：
--   1. 与交付物 mall/docs/db/m2-schema.sql 保持一致，两者任一改动须同步另一方。
--   2. 入驻申请信息存 merchant 草稿字段（subject_name/license_no/license_img/
--      shop_name/shop_logo/shop_intro），平台审核通过时才生成 shop 行——
--      shop 表内全是"已通过"的店，买家端查询干净。
--   3. 通用字段：create_time / update_time / deleted（逻辑删除）；主键雪花 ID。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. merchant 扩展：入驻申请草稿 + 审核备注
-- -----------------------------------------------------------------------------
ALTER TABLE `merchant`
    ADD COLUMN `subject_name` VARCHAR(64)  DEFAULT NULL COMMENT '主体名称（公司/个体户全称）' AFTER `contact_phone`,
    ADD COLUMN `license_no`   VARCHAR(64)  DEFAULT NULL COMMENT '统一社会信用代码/证件号' AFTER `subject_name`,
    ADD COLUMN `license_img`  VARCHAR(255) DEFAULT NULL COMMENT '证件照 URL' AFTER `license_no`,
    ADD COLUMN `shop_name`    VARCHAR(64)  DEFAULT NULL COMMENT '申请开店的店铺名称（草稿）' AFTER `license_img`,
    ADD COLUMN `shop_logo`    VARCHAR(255) DEFAULT NULL COMMENT '店铺 Logo URL（草稿）' AFTER `shop_name`,
    ADD COLUMN `shop_intro`   VARCHAR(500) DEFAULT NULL COMMENT '店铺简介（草稿）' AFTER `shop_logo`,
    ADD COLUMN `audit_remark` VARCHAR(255) DEFAULT NULL COMMENT '审核备注（驳回原因）' AFTER `audit_status`;

-- -----------------------------------------------------------------------------
-- 2. 店铺（仅审核通过的商家有记录，merchant_id 唯一）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `shop` (
    `id`          BIGINT       NOT NULL COMMENT '店铺 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `merchant_id` BIGINT       NOT NULL COMMENT '入驻商家账号 ID（merchant.id）',
    `name`        VARCHAR(64)  NOT NULL COMMENT '店铺名称',
    `logo`        VARCHAR(255)          DEFAULT NULL COMMENT '店铺 Logo URL',
    `intro`       VARCHAR(500)          DEFAULT NULL COMMENT '店铺简介',
    `notice`      VARCHAR(500)          DEFAULT NULL COMMENT '店铺公告',
    `biz_status`  TINYINT      NOT NULL DEFAULT 1 COMMENT '营业状态：1 营业，0 休店',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shop_merchant` (`merchant_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '店铺';

-- -----------------------------------------------------------------------------
-- 3. 平台类目（parent_id 树，顶级为 0）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
    `id`          BIGINT      NOT NULL COMMENT '类目 ID（雪花，种子数据用显式小 ID）',
    `parent_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父类目 ID，顶级为 0',
    `name`        VARCHAR(32) NOT NULL COMMENT '类目名称',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '同级排序（升序）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_category_parent` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '平台类目';

-- -----------------------------------------------------------------------------
-- 4. 商品 SPU
--   status 状态机：0 草稿 / 1 待审核 / 2 已上架 / 3 审核驳回 / 4 商家下架 / 5 平台违规下架
--   仅 status=2 对买家可见；price 冗余 = SKU 最低价，列表页免聚合。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product` (
    `id`            BIGINT         NOT NULL COMMENT 'SPU ID（雪花，应用侧 ASSIGN_ID 生成）',
    `shop_id`       BIGINT         NOT NULL COMMENT '所属店铺 ID（数据级鉴权依据）',
    `category_id`   BIGINT         NOT NULL COMMENT '平台类目 ID',
    `name`          VARCHAR(64)    NOT NULL COMMENT '商品名称',
    `sub_title`     VARCHAR(128)            DEFAULT NULL COMMENT '副标题/卖点',
    `main_img`      VARCHAR(255)   NOT NULL COMMENT '主图 URL',
    `img_list`      VARCHAR(1000)           DEFAULT NULL COMMENT '图集 URL，逗号分隔',
    `price`         DECIMAL(10, 2) NOT NULL COMMENT '展示价（SKU 最低价冗余）',
    `sales`         INT            NOT NULL DEFAULT 0 COMMENT '销量（M3 订单域维护）',
    `avg_rating`    DECIMAL(2, 1)  NOT NULL DEFAULT 5.0 COMMENT '平均评分（M5 评价域维护）',
    `comment_count` INT            NOT NULL DEFAULT 0 COMMENT '评价数（M5 评价域维护）',
    `status`        TINYINT        NOT NULL DEFAULT 0 COMMENT '0 草稿/1 待审核/2 已上架/3 审核驳回/4 商家下架/5 平台违规下架',
    `audit_remark`  VARCHAR(255)            DEFAULT NULL COMMENT '审核备注（驳回/违规下架原因）',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_product_category_status` (`category_id`, `status`),
    KEY `idx_product_shop_status` (`shop_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '商品 SPU';

-- -----------------------------------------------------------------------------
-- 5. 商品 SKU（spec_json 为规格值组合，如 {"颜色":"曜石黑","容量":"256G"}）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_sku` (
    `id`          BIGINT         NOT NULL COMMENT 'SKU ID（雪花，应用侧 ASSIGN_ID 生成）',
    `spu_id`      BIGINT         NOT NULL COMMENT '所属 SPU ID',
    `spec_json`   VARCHAR(500)   NOT NULL COMMENT '规格值组合 JSON 对象串',
    `price`       DECIMAL(10, 2) NOT NULL COMMENT '销售价',
    `stock`       INT            NOT NULL DEFAULT 0 COMMENT '库存',
    `sku_img`     VARCHAR(255)            DEFAULT NULL COMMENT 'SKU 图（规格图）',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_sku_spu` (`spu_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '商品 SKU';

-- -----------------------------------------------------------------------------
-- 6. SPU 规格定义（每 SPU 1~3 行；spec_values 为 JSON 数组串）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_spec` (
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花，应用侧 ASSIGN_ID 生成）',
    `spu_id`      BIGINT       NOT NULL COMMENT '所属 SPU ID',
    `spec_name`   VARCHAR(32)  NOT NULL COMMENT '规格名，如"颜色"',
    `spec_values` VARCHAR(500) NOT NULL COMMENT '可选值 JSON 数组串，如 ["曜石黑","月牙白"]',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_spec_spu` (`spu_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = 'SPU 规格定义';

-- -----------------------------------------------------------------------------
-- 7. 上传文件记录（文件本体在本地 uploads/，经 /static/** 映射访问）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `file_record` (
    `id`            BIGINT      NOT NULL COMMENT '文件 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `original_name` VARCHAR(255) NOT NULL COMMENT '原始文件名（仅展示，不参与存储路径）',
    `stored_name`   VARCHAR(128) NOT NULL COMMENT '存储文件名（UUID.扩展名，yyyyMM 子目录）',
    `url`           VARCHAR(255) NOT NULL COMMENT '对外访问 URL，如 /static/202610/uuid.jpg',
    `size`          BIGINT      NOT NULL COMMENT '字节数',
    `content_type`  VARCHAR(64) NOT NULL COMMENT 'MIME 类型',
    `uploader_type` VARCHAR(16) NOT NULL COMMENT '上传者类型：USER / MERCHANT / ADMIN',
    `uploader_id`   BIGINT      NOT NULL COMMENT '上传者账号 ID',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_file_uploader` (`uploader_type`, `uploader_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '上传文件记录';
