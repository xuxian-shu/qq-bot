-- =====================================================================
-- P1 经济/风控修复的库表变更（MySQL，执行一次）
-- 对应改动：积分流水、群打卡记录、每日一题绑群、违规计数固定窗口
-- 新代码启动前务必先跑本脚本；跑完再核对文末的索引自检清单。
-- =====================================================================

-- ① 积分流水表：每一次加分/扣分留一行底账，供对账/申诉/反作弊。
--    业务代码只 INSERT，永不 UPDATE/DELETE。
CREATE TABLE IF NOT EXISTS `points_log` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `qq`            BIGINT      NOT NULL,
    `delta`         INT         NOT NULL COMMENT '变动值，正=收入 负=支出',
    `balance_after` INT         NOT NULL COMMENT '变动后余额快照',
    `reason`        VARCHAR(64) NOT NULL COMMENT '变动原因：每日签到/每日一题抢答/违规扣分/积分抽奖…',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_qq_time` (`qq`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '积分流水';

-- ② 群打卡表：签到发分是全局每天一次，但排行要按群看"谁在本群露过签到脸"。
--    INSERT IGNORE 靠唯一索引去重，同人同群同天只有一条。
CREATE TABLE IF NOT EXISTS `sign_in_group` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `qq`          BIGINT   NOT NULL,
    `group_id`    BIGINT   NOT NULL,
    `sign_date`   DATE     NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_qq_group_date` (`qq`, `group_id`, `sign_date`),
    KEY `idx_group_date` (`group_id`, `sign_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '每日群打卡（排行统计口径）';

-- ③ 每日一题绑群：抢答只允许发生在出题的群，堵掉跨群白嫖 +20。
--    已存在的表若没有该列，执行下面这句（MySQL 8 无 IF NOT EXISTS，重复执行报列已存在可忽略）：
ALTER TABLE `quiz_question`
    ADD COLUMN `group_id` BIGINT NULL COMMENT '出题的目标群；NULL=历史数据不绑群' AFTER `used_date`;

-- ④ 索引自检清单（并发防线的地基，缺一个对应功能就会被连点击穿）：
--    跑下面的 SELECT，三行必须都是 1，是 0 的补执行对应 ALTER。
--    注意：表里有重复数据时要先清理再加唯一索引，否则 ALTER 会失败。
--
--    SELECT COUNT(*) FROM information_schema.STATISTICS
--     WHERE table_schema = DATABASE() AND table_name = 'user'          AND index_name = 'uk_user_qq';
--    ALTER TABLE `user` ADD UNIQUE KEY `uk_user_qq` (`qq`);
--
--    SELECT COUNT(*) FROM information_schema.STATISTICS
--     WHERE table_schema = DATABASE() AND table_name = 'sign_in_record' AND index_name = 'uk_qq_date';
--    ALTER TABLE `sign_in_record` ADD UNIQUE KEY `uk_qq_date` (`qq`, `sign_date`);
