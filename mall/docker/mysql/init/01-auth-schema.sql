-- =============================================================================
-- M1 认证域建表脚本：买家 / 商家 / 平台管理员 三端账号表
--   引擎 InnoDB，字符集 utf8mb4（与 docker-compose 的启动参数一致）
--   执行方式：docker mysql 容器首次启动时由 docker-entrypoint-initdb.d 自动执行；
--             本机开发库可直接 mysql < 01-auth-schema.sql
-- 说明：三端账号彼此独立、互不关联；密码一律 BCrypt（60 字符），VARCHAR(64) 留余量。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 买家账号
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL COMMENT '账号 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `username`    VARCHAR(32)  NOT NULL COMMENT '登录用户名',
    `password`    VARCHAR(64)  NOT NULL COMMENT '密码哈希（BCrypt）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常，0 禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '买家账号';

-- -----------------------------------------------------------------------------
-- 入驻商家账号
--   audit_status=0 时允许登录（用于提交/查看入驻进度），但登录态不含 shopId，
--   待平台审核通过后才会绑定店铺——详见 spec 的商家入驻流程。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `merchant` (
    `id`            BIGINT      NOT NULL COMMENT '账号 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `username`      VARCHAR(32) NOT NULL COMMENT '登录用户名',
    `password`      VARCHAR(64) NOT NULL COMMENT '密码哈希（BCrypt）',
    `contact_phone` VARCHAR(20)          DEFAULT NULL COMMENT '联系人手机号',
    `audit_status`  TINYINT     NOT NULL DEFAULT 0 COMMENT '入驻审核状态：0 待审核，1 已通过，2 已驳回',
    `status`        TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 正常，0 冻结',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_merchant_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '入驻商家账号';

-- -----------------------------------------------------------------------------
-- 平台管理员账号（不开放自助注册，账号由平台维护）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`          BIGINT       NOT NULL COMMENT '账号 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `username`    VARCHAR(32)  NOT NULL COMMENT '登录用户名',
    `password`    VARCHAR(64)  NOT NULL COMMENT '密码哈希（BCrypt）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常，0 禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '平台管理员账号';

-- -----------------------------------------------------------------------------
-- 种子数据：内置管理员 admin / admin123（密码为 BCrypt 哈希，明文不入库）
-- -----------------------------------------------------------------------------
INSERT INTO `sys_user` (`id`, `username`, `password`, `status`)
VALUES (1, 'admin', '$2a$10$eTxo9trUjlGSkDnDtRxeg.DAC6dlf9yDPIIkdbnhpRW18uWaQdk4i', 1)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `status` = VALUES(`status`);
