-- =============================================================================
-- 多商家入驻 B2B2C 商城 —— M2（商品域）数据库设计
-- -----------------------------------------------------------------------------
-- 数据库：mall          引擎：InnoDB        字符集：utf8mb4 / utf8mb4_general_ci
-- 说明：
--   1. 本文件是 M2 的**数据库设计交付物**，增量于 m1-schema.sql（账号 + RBAC），
--      内容与 docker 初始化脚本保持一致：
--      - mall/docker/mysql/init/03-shop-product-schema.sql（本文件第一~七节）
--      - mall/docker/mysql/init/04-m2-seed.sql（本文件第八节种子数据）
--      两者任一改动，另一方须同步。
--   2. 前置依赖：须先执行 m1-schema.sql（本文件含对 merchant 表的 ALTER）。
--   3. 入驻申请信息存 merchant 草稿字段（subject_name/license_no/license_img/
--      shop_name/shop_logo/shop_intro），平台审核通过时才生成 shop 行——
--      shop 表内全是"已通过"的店，买家端查询干净。
--   4. 通用字段：create_time / update_time / deleted（逻辑删除）；主键雪花 ID
--      （应用侧 MyBatis-Plus IdType.ASSIGN_ID 生成，种子类目用显式小 ID）。
--   5. 商品富文本详情与审核留痕存 MongoDB（见文末"MongoDB 集合设计"）；
--      类目树/商品详情/热搜词用 Redis 缓存（见文末"Redis Key 设计"）。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- =============================================================================
-- 一、merchant 扩展：入驻申请草稿 + 审核备注（增量 ALTER，基于 m1-schema.sql）
-- =============================================================================
ALTER TABLE `merchant`
    ADD COLUMN `subject_name` VARCHAR(64)  DEFAULT NULL COMMENT '主体名称（公司/个体户全称）' AFTER `contact_phone`,
    ADD COLUMN `license_no`   VARCHAR(64)  DEFAULT NULL COMMENT '统一社会信用代码/证件号' AFTER `subject_name`,
    ADD COLUMN `license_img`  VARCHAR(255) DEFAULT NULL COMMENT '证件照 URL' AFTER `license_no`,
    ADD COLUMN `shop_name`    VARCHAR(64)  DEFAULT NULL COMMENT '申请开店的店铺名称（草稿）' AFTER `license_img`,
    ADD COLUMN `shop_logo`    VARCHAR(255) DEFAULT NULL COMMENT '店铺 Logo URL（草稿）' AFTER `shop_name`,
    ADD COLUMN `shop_intro`   VARCHAR(500) DEFAULT NULL COMMENT '店铺简介（草稿）' AFTER `shop_logo`,
    ADD COLUMN `audit_remark` VARCHAR(255) DEFAULT NULL COMMENT '审核备注（驳回原因）' AFTER `audit_status`;

-- =============================================================================
-- 二、店铺（仅审核通过的商家有记录，merchant_id 唯一）
-- =============================================================================
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

-- =============================================================================
-- 三、平台类目（parent_id 树，顶级为 0）
-- =============================================================================
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

-- =============================================================================
-- 四、商品 SPU
--   status 状态机：0 草稿 / 1 待审核 / 2 已上架 / 3 审核驳回 / 4 商家下架 / 5 平台违规下架
--   仅 status=2 对买家可见；price 冗余 = SKU 最低价，列表页免聚合。
-- =============================================================================
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

-- =============================================================================
-- 五、商品 SKU（spec_json 为规格值组合，如 {"颜色":"曜石黑","容量":"256G"}）
-- =============================================================================
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

-- =============================================================================
-- 六、SPU 规格定义（每 SPU 1~3 行；spec_values 为 JSON 数组串）
-- =============================================================================
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

-- =============================================================================
-- 七、上传文件记录（文件本体在本地 uploads/，经 /static/** 映射访问）
-- =============================================================================
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
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_file_uploader` (`uploader_type`, `uploader_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '上传文件记录';

-- =============================================================================
-- 八、种子数据
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 8.1 演示类目树（2 级，显式小 ID 便于演示与排错）
-- -----------------------------------------------------------------------------
INSERT INTO `category` (`id`, `parent_id`, `name`, `sort`, `status`)
VALUES (101, 0, '数码家电', 1, 1),
       (102, 101, '手机', 1, 1),
       (103, 101, '电脑', 2, 1),
       (104, 101, '耳机', 3, 1),
       (105, 0, '服饰鞋包', 2, 1),
       (106, 105, '男装', 1, 1),
       (107, 105, '女装', 2, 1),
       (108, 0, '食品生鲜', 3, 1),
       (109, 108, '休闲零食', 1, 1),
       (110, 108, '新鲜水果', 2, 1)
ON DUPLICATE KEY UPDATE `parent_id` = VALUES(`parent_id`), `name` = VALUES(`name`),
                        `sort` = VALUES(`sort`), `status` = VALUES(`status`);

-- -----------------------------------------------------------------------------
-- 8.2 商品域权限/菜单（type=MENU 出现在侧边栏，type=BUTTON 仅细粒度授权）
--    sys_permission 主键沿用 M1 约定（M1 已占用 1-4，本节用 10-28）；
--    全部绑定到内置角色 ROLE_SUPER(id=1)。
--    菜单结构：商家管理 → 入驻审核；商品管理 → 类目管理 / 商品审核 / 商品管控
-- -----------------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `code`, `type`, `path`, `sort`, `status`)
VALUES (10, 0, '商家管理', 'merchant', 'MENU', '/merchant', 20, 1),
       (11, 10, '入驻审核', 'merchant:audit:list', 'MENU', '/merchant/audit', 21, 1),
       (12, 11, '审核处理', 'merchant:audit:handle', 'BUTTON', NULL, 1, 1),
       (13, 11, '冻结/启用', 'merchant:manage:status', 'BUTTON', NULL, 2, 1),
       (20, 0, '商品管理', 'goods', 'MENU', '/goods', 30, 1),
       (21, 20, '类目管理', 'product:category:list', 'MENU', '/goods/category', 31, 1),
       (22, 21, '新增类目', 'product:category:add', 'BUTTON', NULL, 1, 1),
       (23, 21, '编辑类目', 'product:category:edit', 'BUTTON', NULL, 2, 1),
       (24, 21, '删除类目', 'product:category:delete', 'BUTTON', NULL, 3, 1),
       (25, 20, '商品审核', 'product:audit:list', 'MENU', '/goods/audit', 32, 1),
       (26, 25, '审核处理', 'product:audit:handle', 'BUTTON', NULL, 1, 1),
       (27, 20, '商品管控', 'product:manage:list', 'MENU', '/goods/manage', 33, 1),
       (28, 27, '违规下架', 'product:manage:off', 'BUTTON', NULL, 1, 1)
ON DUPLICATE KEY UPDATE `parent_id` = VALUES(`parent_id`), `name` = VALUES(`name`),
                        `type` = VALUES(`type`), `path` = VALUES(`path`),
                        `sort` = VALUES(`sort`), `status` = VALUES(`status`);

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
VALUES (1, 10), (1, 11), (1, 12), (1, 13),
       (1, 20), (1, 21), (1, 22), (1, 23), (1, 24), (1, 25), (1, 26), (1, 27), (1, 28)
ON DUPLICATE KEY UPDATE `permission_id` = VALUES(`permission_id`);

-- =============================================================================
-- MongoDB 集合设计（mongosh 参考；应用由 Spring Data @Document/@Indexed 自动建索引，
-- 需 spring.data.mongodb.auto-index-creation: true）
-- =============================================================================
--
-- 【product_detail】商品富文本详情，与 MySQL product 一一对应
--   字段：_id ObjectId | spuId Long（唯一）| html String（wangEditor 富文本）
--         gallery [String]（详情图集 URL）| updateTime Date
--   索引：db.product_detail.createIndex({spuId: 1}, {unique: true})
--
-- 【merchant_audit_log】审核留痕（入驻申请与商品审核共用）
--   字段：_id ObjectId | targetType "MERCHANT_APPLY"|"PRODUCT" | targetId Long
--         action "SUBMIT"|"PASS"|"REJECT"|"VIOLATION_OFF" | operator String（如 "ADMIN:1"）
--         remark String（驳回/违规原因）| createTime Date
--   索引：db.merchant_audit_log.createIndex({targetId: 1})
--
-- =============================================================================
-- Redis Key 设计（M2 新增；M1 的 mall:captcha:{key} / mall:jwt:* 见 m1 文档）
-- =============================================================================
--
-- 【mall:category:tree】STRING，类目树 JSON（CategoryNode 数组）
--   无 TTL；写操作（新增/编辑/启停/删除类目）后删键，下一次读取重建；
--   缓存损坏按 miss 处理回源重建。
--
-- 【mall:product:{spuId}】STRING，商品详情聚合 JSON（ProductDetailVO 整体）
--   TTL 30 分钟；商品编辑/审核/上下架/违规下架后删键重建；
--   缓存损坏按 miss 处理；错误结果（未上架）不缓存。
--
-- 【mall:hot:search】ZSET，热搜词榜（member=归一化关键词，score=搜索次数）
--   搜索接口 keyword 非空时 ZINCRBY +1；榜单取 ZREVRANGE Top10。
-- =============================================================================
