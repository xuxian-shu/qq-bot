-- 敏感词风控相关表结构（MySQL）
-- 执行一次即可，之后直接往 sensitive_word 表里增删词语，
-- 机器人每 60 秒自动刷新到内存，不用重启。

CREATE TABLE IF NOT EXISTS `sensitive_word` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `word`        VARCHAR(64)  NOT NULL COMMENT '敏感词，启用后在内存中匹配',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用，0=停用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='敏感词库';

-- 按需维护。建议先从少量高置信度词开始，观察误杀再扩充。
-- INSERT INTO `sensitive_word` (`word`) VALUES ('示例违规词');

-- 强烈建议给 user.qq 建唯一索引：
-- 1. 新人建档防并发重复插入
-- 2. 退群再进不会重复产生用户记录
-- 如果表里已有重复 qq，请先清理数据再执行。
-- ALTER TABLE `user` ADD UNIQUE KEY `uk_user_qq` (`qq`);