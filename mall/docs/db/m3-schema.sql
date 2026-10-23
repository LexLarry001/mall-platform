-- =============================================================================
-- 多商家入驻 B2B2C 商城 —— M3（交易域）数据库设计
-- -----------------------------------------------------------------------------
-- 数据库：mall          引擎：InnoDB        字符集：utf8mb4 / utf8mb4_general_ci
-- 说明：
--   1. 本文件是 M3 的**数据库设计交付物**，增量于 m1-schema.sql（账号+RBAC）
--      与 m2-schema.sql（商品域），内容与 docker 初始化脚本保持一致：
--      - mall/docker/mysql/init/05-order-schema.sql（本文件第一~四节）
--      - mall/docker/mysql/init/06-m3-seed.sql（本文件第五节种子数据）
--      两者任一改动，另一方须同步。
--   2. 前置依赖：须先执行 m1-schema.sql、m2-schema.sql。
--   3. `order` 为 MySQL 保留字：DDL、@TableName、XML 一律反引号。
--   4. 订单行为事实快照，不做逻辑删除（无 deleted 列）；金额一律 DECIMAL(10,2)。
--   5. 主键雪花 ID（应用侧 MyBatis-Plus IdType.ASSIGN_ID 生成，无 AUTO_INCREMENT），
--      SQL 直插须显式 id。
--   6. 购物车本体不落 MySQL（Redis Hash 即存储，见文末"Redis Key 设计"），
--      下单成功后从 Redis 清除已勾选项。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- =============================================================================
-- 一、买家收货地址（同一买家仅一条 is_default=1，由应用事务保证互斥）
-- =============================================================================
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

-- =============================================================================
-- 二、主订单（`order` 为保留字）
--   status 状态机（M3 部分）：0 待支付 ──取消/超时──> 3 已取消；
--   1 已支付 / 2 已完成 由 M4 支付、M5 收货推进。
--   receiver/phone/full_address 为下单时的地址快照，防地址删改影响历史订单。
-- =============================================================================
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

-- =============================================================================
-- 三、子订单（按 shop 拆单，一店一单）
--   status 状态机（M3 部分）：0 待支付 ──取消/超时──> 4 已取消；
--   1 待发货 / 2 待收货 / 3 已完成 由 M4 支付回调、M5 发货收货推进。
-- =============================================================================
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

-- =============================================================================
-- 四、订单项（商品快照：名称/主图/规格/单价，防后续改价改图影响历史订单）
-- =============================================================================
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

-- =============================================================================
-- 五、M3 种子数据：演示买家 buyer01（密码 a123456）+ 收货地址 2 条
--   说明：
--     1. 密码哈希复用 a123456 的 BCrypt（与演示商家 m2ui001 同口令，便于演示记忆）。
--     2. buyer01 可能已被注册接口以雪花 ID 建过：账号按 username 幂等 upsert，
--        地址按 username 动态解析 user_id，不假设固定 ID。
--     3. 商品/店铺数据复用 M2 种子（04-m2-seed.sql 及 m2ui001 店铺在售商品），
--        本文件不重复造。
-- =============================================================================

-- 5.1 演示买家 buyer01 / a123456
--     username 唯一：已存在则刷新密码与状态，ID 以库内为准（注册接口建的为雪花 ID）
INSERT INTO `user` (`id`, `username`, `password`, `status`)
VALUES (101, 'buyer01', '$2a$10$hI4Gz2r2yLNv5Zp7Q/vKG.G/oN8Bp9dgY9jZtp2Q7Hpkv83oLHgiG', 1)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `status` = VALUES(`status`);

-- 5.2 buyer01 收货地址 2 条（1011 为默认；user_id 按 username 动态解析）
INSERT INTO `user_address` (`id`, `user_id`, `receiver`, `phone`, `province`, `city`, `district`, `detail`, `is_default`)
SELECT 1011, u.id, '张三', '13800000001', '浙江省', '杭州市', '西湖区', '文三路 100 号 1 幢 301 室', 1
FROM `user` u WHERE u.username = 'buyer01'
ON DUPLICATE KEY UPDATE `user_id` = VALUES(`user_id`), `receiver` = VALUES(`receiver`),
                        `phone` = VALUES(`phone`), `province` = VALUES(`province`),
                        `city` = VALUES(`city`), `district` = VALUES(`district`),
                        `detail` = VALUES(`detail`), `is_default` = VALUES(`is_default`);

INSERT INTO `user_address` (`id`, `user_id`, `receiver`, `phone`, `province`, `city`, `district`, `detail`, `is_default`)
SELECT 1012, u.id, '李四', '13800000002', '江苏省', '南京市', '鼓楼区', '中山北路 200 号 5 楼', 0
FROM `user` u WHERE u.username = 'buyer01'
ON DUPLICATE KEY UPDATE `user_id` = VALUES(`user_id`), `receiver` = VALUES(`receiver`),
                        `phone` = VALUES(`phone`), `province` = VALUES(`province`),
                        `city` = VALUES(`city`), `district` = VALUES(`district`),
                        `detail` = VALUES(`detail`), `is_default` = VALUES(`is_default`);

-- =============================================================================
-- Redis Key 设计（M3 新增；M1/M2 已有 Key 见 m2-schema.sql 文末）
-- =============================================================================
--
-- 1. mall:cart:{userId}
--    类型：Hash
--    field：skuId（雪花 Long 字符串）
--    value：JSON 对象 {"quantity": 2, "checked": true}
--    说明：购物车本体即存储（不落 MySQL 兜底，毕设场景 Redis 故障清空可接受）。
--          加购同款合并数量；列表实时联查 SKU/SPU 打标 invalid（下架/删除/库存 0）；
--          下单成功后删除已勾选项（field 级 HDEL）。坏 JSON 条目读取时自愈删除。
--    生命周期：随用户操作增删，无 TTL（活跃买家数据量小）。
--
-- 2. mall:order:timeout
--    类型：ZSet
--    member：主订单号（M+雪花字符串）
--    score：支付截止时间戳（毫秒，expire_time 同一时刻）
--    说明：超时关单锚点。OrderTimeoutJob 每 60s ZRANGEBYSCORE(0, now] 取到期订单，
--          幂等关单（仅 0→3 生效）后 ZREM；支付成功（M4）或主动取消时同步 ZREM。
--
-- 3. mall:order:submit:{userId}
--    类型：String
--    value："1"
--    说明：防重复提交锁。提交订单时 SET NX EX 5s，未抢到即"请勿重复提交"。
--          锁随事务结束自然过期（不主动 DEL，避免误删并发后到的锁）。
--
-- =============================================================================
