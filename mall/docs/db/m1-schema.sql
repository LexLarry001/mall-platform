-- =============================================================================
-- 多商家入驻 B2B2C 商城 —— M1（骨架 + 认证授权）数据库设计
-- -----------------------------------------------------------------------------
-- 数据库：mall          引擎：InnoDB        字符集：utf8mb4 / utf8mb4_general_ci
-- 说明：
--   1. 本文件是 M1 的**数据库设计交付物**，内容与 docker 初始化脚本保持一致：
--      - mall/docker/mysql/init/01-auth-schema.sql（买家/商家/管理员账号）
--      - mall/docker/mysql/init/02-rbac-schema.sql（RBAC 角色/权限/菜单）
--      两者任一改动，另一方须同步（见 docs/api/m1-auth-api.md 的维护说明）。
--   2. Docker 部署时由 mysql 容器 docker-entrypoint-initdb.d 自动执行；
--      本机开发库（MySQL 8.0，root/123456@localhost:3306）可直接执行本文件。
--   3. 全部主键为雪花 ID（应用侧 MyBatis-Plus IdType.ASSIGN_ID 生成），
--      通用字段：create_time / update_time / deleted（逻辑删除）。
--   4. 密码一律 BCrypt 存储（$2a$ 前缀、60 字符），字段留 VARCHAR(64) 余量。
--   5. 三端账号（user / merchant / sys_user）彼此独立、不共享表；管理员不开放自助注册。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `mall`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `mall`;

SET NAMES utf8mb4;

-- =============================================================================
-- 一、账号域（认证主体）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1.1 买家账号
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
-- 1.2 入驻商家账号
--   audit_status=0 时允许登录（用于提交/查看入驻进度），但登录态不含 shopId，
--   待平台审核通过后才会绑定店铺——详见 spec 的商家入驻流程（M2+）。
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
-- 1.3 平台管理员账号（不开放自助注册，账号由平台维护）
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

-- =============================================================================
-- 二、权限域（RBAC：角色 / 权限（含菜单）/ 两张关联表）
--   设计取舍：管理端菜单与权限共用 sys_permission 一张表 —— type=MENU 的节点
--   既是权限码也是菜单项；type=BUTTON 的节点只作细粒度授权，不出现在侧边栏。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 2.1 平台角色
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`          BIGINT      NOT NULL COMMENT '角色 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `name`        VARCHAR(32) NOT NULL COMMENT '角色名称',
    `code`        VARCHAR(32) NOT NULL COMMENT '角色标识（ROLE_ 前缀）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '平台角色';

-- -----------------------------------------------------------------------------
-- 2.2 平台权限 / 菜单项
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`          BIGINT      NOT NULL COMMENT '权限 ID（雪花，应用侧 ASSIGN_ID 生成）',
    `parent_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父节点 ID，顶级为 0',
    `name`        VARCHAR(32) NOT NULL COMMENT '名称（菜单标题）',
    `code`        VARCHAR(64) NOT NULL COMMENT '权限码（同时作为菜单标识）',
    `type`        VARCHAR(16) NOT NULL COMMENT '类型：MENU 菜单，BUTTON 按钮',
    `path`        VARCHAR(128)         DEFAULT NULL COMMENT '前端路由路径，仅 MENU 有值',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '同级排序（升序）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_permission_code` (`code`),
    KEY `idx_sys_permission_parent` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '平台权限与菜单项';

-- -----------------------------------------------------------------------------
-- 2.3 管理员 - 角色（多对多）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `user_id`     BIGINT   NOT NULL COMMENT '管理员账号 ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_sys_user_role_role` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '管理员与角色关联';

-- -----------------------------------------------------------------------------
-- 2.4 角色 - 权限（多对多）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `role_id`       BIGINT   NOT NULL COMMENT '角色 ID',
    `permission_id` BIGINT   NOT NULL COMMENT '权限 ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_sys_role_permission_permission` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '角色与权限关联';

-- =============================================================================
-- 三、种子数据
--   3.1 内置管理员 admin / admin123（明文不入库，此处为 BCrypt 哈希）
--   3.2 超级管理员角色 ROLE_SUPER（拥有 M1 全部权限）绑定 admin
--   3.3 菜单树：系统管理 → 用户管理 / 角色管理；新增用户为按钮级权限，不入菜单
-- =============================================================================

INSERT INTO `sys_user` (`id`, `username`, `password`, `status`)
VALUES (1, 'admin', '$2a$10$eTxo9trUjlGSkDnDtRxeg.DAC6dlf9yDPIIkdbnhpRW18uWaQdk4i', 1)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `status` = VALUES(`status`);

INSERT INTO `sys_role` (`id`, `name`, `code`, `status`)
VALUES (1, '超级管理员', 'ROLE_SUPER', 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = VALUES(`status`);

INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `code`, `type`, `path`, `sort`, `status`)
VALUES (1, 0, '系统管理', 'system', 'MENU', '/system', 10, 1),
       (2, 1, '用户管理', 'system:user:list', 'MENU', '/system/user', 11, 1),
       (3, 1, '角色管理', 'system:role:list', 'MENU', '/system/role', 12, 1),
       (4, 2, '新增用户', 'system:user:add', 'BUTTON', NULL, 1, 1),
       (5, 2, '编辑用户', 'system:user:edit', 'BUTTON', NULL, 2, 1),
       (6, 2, '删除用户', 'system:user:delete', 'BUTTON', NULL, 3, 1),
       (7, 3, '新增角色', 'system:role:add', 'BUTTON', NULL, 1, 1),
       (8, 3, '编辑角色', 'system:role:edit', 'BUTTON', NULL, 2, 1),
       (9, 3, '删除角色', 'system:role:delete', 'BUTTON', NULL, 3, 1)
ON DUPLICATE KEY UPDATE `parent_id` = VALUES(`parent_id`), `name` = VALUES(`name`),
                        `type` = VALUES(`type`), `path` = VALUES(`path`),
                        `sort` = VALUES(`sort`), `status` = VALUES(`status`);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
VALUES (1, 1)
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
VALUES (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9)
ON DUPLICATE KEY UPDATE `permission_id` = VALUES(`permission_id`);

-- =============================================================================
-- 四、索引与约束说明
--   uk_user_username / uk_merchant_username / uk_sys_user_username
--       三端用户名在各自表内唯一；重复注册由服务层先行判定并返回业务错误
--       "用户名已存在"，避免把数据库唯一键冲突暴露成 500。
--   uk_sys_role_code / uk_sys_permission_code
--       角色标识与权限码唯一，保证 @PreAuthorize("hasAuthority('xxx')") 的码值稳定。
--   idx_sys_permission_parent      支撑"按 parentId 组菜单树"的查询。
--   sys_user_role / sys_role_permission 复合主键天然去重；反向索引用于"按角色查成员"。
-- =============================================================================
