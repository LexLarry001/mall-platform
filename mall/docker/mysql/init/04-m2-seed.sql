-- =============================================================================
-- M2 种子数据：演示类目树 + 商品域 RBAC 权限/菜单（绑定 ROLE_SUPER）
-- 说明：
--   1. 与交付物 mall/docs/db/m2-schema.sql 保持一致。
--   2. sys_permission 主键沿用 M1 约定（M1 已占用 1-4，本文件用 10-28）；
--      全部绑定到内置角色 ROLE_SUPER(id=1)，低权测试账号 auditor 不绑。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 演示类目树（2 级，显式小 ID 便于演示与排错）
--    定位：家具商城网购平台，类目围绕客厅/卧室/餐厅/书房/软装/屏风配件展开
-- -----------------------------------------------------------------------------
INSERT INTO `category` (`id`, `parent_id`, `name`, `sort`, `status`)
VALUES (101, 0, '客厅家具', 1, 1),
       (102, 101, '沙发', 1, 1),
       (103, 101, '茶几', 2, 1),
       (104, 101, '电视柜', 3, 1),
       (105, 101, '休闲椅', 4, 1),
       (106, 101, '角几', 5, 1),
       (201, 0, '卧室家具', 2, 1),
       (202, 201, '双人床', 1, 1),
       (203, 201, '床榻', 2, 1),
       (204, 201, '床头柜', 3, 1),
       (205, 201, '梳妆台', 4, 1),
       (206, 201, '衣柜', 5, 1),
       (301, 0, '餐厅家具', 3, 1),
       (302, 301, '餐桌', 1, 1),
       (303, 301, '餐椅', 2, 1),
       (304, 301, '餐边柜', 3, 1),
       (305, 301, '吧台椅', 4, 1),
       (401, 0, '书房家具', 4, 1),
       (402, 401, '书桌', 1, 1),
       (403, 401, '办公椅', 2, 1),
       (404, 401, '书架', 3, 1),
       (501, 0, '软装灯饰', 5, 1),
       (502, 501, '灯具', 1, 1),
       (503, 501, '窗帘', 2, 1),
       (504, 501, '抱枕', 3, 1),
       (505, 501, '装饰画', 4, 1),
       (506, 501, '装饰摆件', 5, 1),
       (601, 0, '屏风与配件', 6, 1),
       (602, 601, '屏风', 1, 1),
       (604, 601, '家具配件', 2, 1)
ON DUPLICATE KEY UPDATE `parent_id` = VALUES(`parent_id`), `name` = VALUES(`name`),
                        `sort` = VALUES(`sort`), `status` = VALUES(`status`);

-- -----------------------------------------------------------------------------
-- 2. 商品域权限/菜单（type=MENU 出现在侧边栏，type=BUTTON 仅细粒度授权）
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
