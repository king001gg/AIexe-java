-- ============================================================
-- V2：合并重复会话 + 给 conversations.session_id 加唯一约束（缺陷 D4，H2 版）
--
-- 背景：conversations.session_id 原先只有普通索引，没有唯一约束，而
--       ConversationService.getOrCreateConversation 是「先查后插」——并发首次访问
--       同一个 sessionId 时，多个线程同时查不到就会各自插入，产生多行。
--
--       多行一旦存在，后果是**永久性的**：ConversationRepository.findBySessionId 返回
--       Optional，Spring Data 的单值查询匹配到多行会抛
--       IncorrectResultSizeDataAccessException，此后该 sessionId 的每次请求都 500，
--       且不会自愈（没有代码路径会去清理重复行）。
--
-- 为什么不直接 ADD CONSTRAINT：存量库里可能已经有重复行（而且很可能有——这个竞态不需要
--       多高的并发就能撞上），直接加约束会让迁移失败、应用起不来。必须先把重复行合掉。
--
-- 为什么用一张临时映射表，而不是相关子查询：
--       MySQL 不允许在 UPDATE / DELETE 的子查询里引用被更新的目标表（错误 1093）。
--       先把 (old_id -> keep_id) 物化出来，后面的 UPDATE / DELETE 就只引用这张临时表，
--       H2 与 MySQL 都能跑同一套写法。
--
-- 合并语义：同一个 session_id 保留 id 最小的那条（它的 created_at 才是这个会话的真实起点），
--       把子表指过去，再删掉其余行。**不丢消息、不丢工具调用记录。**
--
-- 与 mysql/V2__*.sql 结构完全一致，本文件无方言差异。
-- ============================================================

-- 1) 物化「重复行 → 保留行」的映射
--
--    刻意不加 IF NOT EXISTS：这张表是一次性脚手架，同名残留只可能来自上一次半途失败的
--    迁移。这种情况应该报错让人来看，而不是静默接着跑（静默续跑会让下面的标量子查询
--    撞上多行，报出一个更难懂的错）。
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

-- 2) 先把子表重新指向保留行，再删会话 —— 顺序不能反
--
--    messages.conversation_id 的外键是 ON DELETE CASCADE：先删会话会把消息一起带走，
--    而且是**静默**带走（不报错、不可恢复）。这一步是整个迁移里最要紧的一步。
UPDATE messages SET conversation_id =
    (SELECT d.keep_id FROM conversation_dedup d WHERE d.old_id = messages.conversation_id)
WHERE conversation_id IN (SELECT old_id FROM conversation_dedup);

--    tool_calls.conversation_id 没有外键，不会被级联删除；但它同样会被
--    ToolCallRepository.findByConversationIdOrderByCreatedAtDesc 按 conversation_id 查询，
--    不重指就会留下指向已删会话的悬空引用——对那些查询而言记录等于消失了。
UPDATE tool_calls SET conversation_id =
    (SELECT d.keep_id FROM conversation_dedup d WHERE d.old_id = tool_calls.conversation_id)
WHERE conversation_id IN (SELECT old_id FROM conversation_dedup);

-- 3) 删掉重复会话（此时子表已全部指走）
DELETE FROM conversations WHERE id IN (SELECT old_id FROM conversation_dedup);

-- 4) 加唯一约束：此后并发创建会话只可能有一个赢家，
--    输的那些由 ConversationService 捕获冲突后重查（见该类的方法注释）
ALTER TABLE conversations ADD CONSTRAINT uk_conversations_session_id UNIQUE (session_id);

-- 5) 拆脚手架
DROP TABLE conversation_dedup;

-- 刻意不做：V1 建的 idx_conversations_session_id 在加了唯一约束后已经冗余，
-- 但删它要引入 DROP INDEX 的厂商差异语法，让这次「修并发缺陷」的迁移顺带承担
-- 索引调整的风险。留作已知的无害冗余。
