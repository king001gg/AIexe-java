-- ============================================================
-- V1 基线：RAG 问答 Agent 初始表结构（MySQL 8）
--
-- 本脚本由实体类（com.ai.rag.model.entity）反推而来，是 schema 的唯一真实来源。
-- 注意：不再包含 CREATE DATABASE / USE —— Flyway 连接的就是目标库，
--       ai_rag_db 需由 DBA 预先创建（datasource url 已指向它）。
-- ============================================================

-- 会话表
CREATE TABLE IF NOT EXISTS conversations (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id  VARCHAR(100) NOT NULL,
    title       VARCHAR(255),
    user_name   VARCHAR(100),
    model       VARCHAR(50),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_conversations_session_id (session_id),
    INDEX idx_conversations_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 消息表
CREATE TABLE IF NOT EXISTS messages (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    tokens          INT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE CASCADE,
    INDEX idx_messages_conversation_id (conversation_id),
    INDEX idx_messages_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 知识库表
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    file_path   VARCHAR(500),
    file_name   VARCHAR(255),
    file_size   BIGINT,
    doc_count   INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by  VARCHAR(100),
    INDEX idx_knowledge_bases_name (name),
    INDEX idx_knowledge_bases_created_by (created_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 知识文档（分块）表
CREATE TABLE IF NOT EXISTS documents (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    title             VARCHAR(500),
    content           TEXT NOT NULL,
    chunk_id          VARCHAR(100) NOT NULL,
    chunk_index       INT,
    tokens            INT,
    vector_id         VARCHAR(100),
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_documents_knowledge_base FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    CONSTRAINT uk_knowledge_chunk UNIQUE (knowledge_base_id, chunk_id),
    INDEX idx_documents_knowledge_base_id (knowledge_base_id),
    INDEX idx_documents_chunk_id (chunk_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 长期记忆表
CREATE TABLE IF NOT EXISTS memories (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id  VARCHAR(100) NOT NULL,
    memory_key  VARCHAR(255) NOT NULL,
    value       TEXT NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_session_key UNIQUE (session_id, memory_key),
    INDEX idx_memories_session_id (session_id),
    INDEX idx_memories_memory_key (memory_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 工具调用记录表
CREATE TABLE IF NOT EXISTS tool_calls (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT,
    session_id      VARCHAR(100),
    tool_name       VARCHAR(100) NOT NULL,
    tool_input      TEXT,
    tool_output     TEXT,
    status          VARCHAR(20) DEFAULT 'SUCCESS',
    error_message   TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tool_calls_conversation_id (conversation_id),
    INDEX idx_tool_calls_session_id (session_id),
    INDEX idx_tool_calls_tool_name (tool_name),
    INDEX idx_tool_calls_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Token 使用统计表
CREATE TABLE IF NOT EXISTS token_usage (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id      VARCHAR(100),
    conversation_id BIGINT,
    input_tokens    INT DEFAULT 0,
    output_tokens   INT DEFAULT 0,
    total_tokens    INT DEFAULT 0,
    cost            DECIMAL(10, 6) DEFAULT 0.000000,
    date            DATE,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_session_date UNIQUE (session_id, date),
    INDEX idx_token_usage_session_id (session_id),
    INDEX idx_token_usage_conversation_id (conversation_id),
    INDEX idx_token_usage_date (date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 用户偏好设置表
CREATE TABLE IF NOT EXISTS user_preferences (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id  VARCHAR(100) NOT NULL,
    nickname    VARCHAR(100),
    ai_name     VARCHAR(100),
    personality VARCHAR(20),
    preferences JSON,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_preferences_session_id UNIQUE (session_id),
    INDEX idx_user_preferences_session_id (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
