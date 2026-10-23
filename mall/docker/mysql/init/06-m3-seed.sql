-- =============================================================================
-- M3 种子数据：演示买家 buyer01（密码 a123456）+ 收货地址 2 条
-- 说明：
--   1. 与交付物 mall/docs/db/m3-schema.sql 保持一致。
--   2. 密码哈希复用 a123456 的 BCrypt（与演示商家 m2ui001 同口令，便于演示记忆）。
--   3. buyer01 可能已被注册接口以雪花 ID 建过：账号按 username 幂等 upsert，
--      地址按 username 动态解析 user_id，不假设固定 ID。
--   4. 商品/店铺数据复用 M2 种子（04-m2-seed.sql 及 m2ui001 店铺在售商品），本文件不重复造。
-- =============================================================================

USE `mall`;

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 演示买家 buyer01 / a123456
--    username 唯一：已存在则刷新密码与状态，ID 以库内为准（注册接口建的为雪花 ID）
-- -----------------------------------------------------------------------------
INSERT INTO `user` (`id`, `username`, `password`, `status`)
VALUES (101, 'buyer01', '$2a$10$hI4Gz2r2yLNv5Zp7Q/vKG.G/oN8Bp9dgY9jZtp2Q7Hpkv83oLHgiG', 1)
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`), `status` = VALUES(`status`);

-- -----------------------------------------------------------------------------
-- 2. buyer01 收货地址 2 条（1011 为默认；user_id 按 username 动态解析）
-- -----------------------------------------------------------------------------
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
