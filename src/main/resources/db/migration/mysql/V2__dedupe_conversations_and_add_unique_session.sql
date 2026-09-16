-- ============================================================
-- V2：合并重复会话 + 给 conversations.session_id 加唯一约束（缺陷 D4，MySQL 8 版）
--
-- 与 h2/V2__*.sql 结构逐句一致（本迁移无方言差异），完整背景见那边的注释。要点：
--
--   1) 先合并重复行，再加唯一约束 —— 存量库里很可能已经有重复行，直接加约束会让迁移
--      失败、应用起不来。
--   2) 用临时映射表而不是相关子查询 —— 绕开 MySQL 错误 1093
--      （不能在 UPDATE / DELETE 的子查询里引用被更新的目标表）。
--   3) **先把 messages / tool_calls 指到保留行，再删会话** ——
--      messages.conversation_id 的外键是 ON DELETE CASCADE，顺序反了会静默删掉消息。
--
-- 注意：MySQL 的 DDL 不在事务里，本迁移若中途失败会留下部分状态，需要人工修复后
--       再用 flyway repair 重跑。这是 MySQL 的固有限制（H2 版可整体回滚）。
-- ============================================================

-- 1) 物化「重复行 → 保留行」的映射（保留每个 session_id 下 id 最小的那条）
CREATE TABLE conversation_dedup (
    old_id  BIGINT NOT NULL,
    keep_id BIGINT NOT NULL
);

INSERT INTO conversation_dedup (old_id, keep_id)
SELECT c.id, k.keep_id
FROM conversations c
JOIN (SELECT session_id, MIN(id) AS keep_id FROM conversations GROUP BY session_id) k
     ON k.session_id = c.session_id
WHERE c.id <> k.keep_id;

-- 2) 子表先重指，再删会话（顺序不能反，见文件头）
UPDATE messages SET conversation_id =
    (SELECT d.keep_id FROM conversation_dedup d WHERE d.old_id = messages.conversation_id)
WHERE conversation_id IN (SELECT old_id FROM conversation_dedup);

UPDATE tool_calls SET conversation_id =
    (SELECT d.keep_id FROM conversation_dedup d WHERE d.old_id = tool_calls.conversation_id)
WHERE conversation_id IN (SELECT old_id FROM conversation_dedup);

-- 3) 删掉重复会话
DELETE FROM conversations WHERE id IN (SELECT old_id FROM conversation_dedup);

-- 4) 加唯一约束
ALTER TABLE conversations ADD CONSTRAINT uk_conversations_session_id UNIQUE (session_id);

-- 5) 拆脚手架
DROP TABLE conversation_dedup;

-- 刻意不做：V1 建的 idx_conversations_session_id 加约束后已冗余，但它无害，
-- 且删除要引入 DROP INDEX 的方言差异语法。留作已知冗余。
