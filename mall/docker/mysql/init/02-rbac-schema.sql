-- =============================================================================
-- M1 RBAC 建表脚本：角色 / 权限（含菜单）/ 管理员-角色 / 角色-权限
--   引擎 InnoDB，字符集 utf8mb4（与 docker-compose 的启动参数一致）
--   执行方式：docker mysql 容器首次启动时由 docker-entrypoint-initdb.d 自动执行；
--             本机开发库可直接 mysql < 02-rbac-schema.sql
-- 说明：管理端菜单与权限共用 sys_permission 一张表——type=MENU 的节点既是权限码也是菜单项，
--       type=BUTTON 的节点只作细粒度授权（不出现在侧边栏）。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 平台角色
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
-- 平台权限 / 菜单项
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
-- 管理员 - 角色（多对多）
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
-- 角色 - 权限（多对多）
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

-- -----------------------------------------------------------------------------
-- 种子数据：超级管理员角色 ROLE_SUPER（全权限）绑给内置管理员 admin(id=1)
--   菜单树：系统管理 → 用户管理 / 角色管理；system:user:add 为按钮级权限，不出现在菜单里
-- -----------------------------------------------------------------------------
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
