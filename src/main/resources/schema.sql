# 数据库初始化脚本

-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_rag_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_rag_db;

-- 会话表
CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(100) NOT NULL,
    title VARCHAR(255),
    user_name VARCHAR(100),
    model VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
);

-- 消息表
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role ENUM('USER', 'ASSISTANT') NOT NULL,
    content TEXT NOT NULL,
    tokens INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_created_at (created_at)
);

-- 知识库表
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    file_path VARCHAR(500),
    file_name VARCHAR(255),
    file_size BIGINT,
    doc_count INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    INDEX idx_name (name),
    INDEX idx_created_by (created_by)
);

-- 知识文档表
CREATE TABLE IF NOT EXISTS documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(500),
    content TEXT NOT NULL,
    chunk_id VARCHAR(100) NOT NULL,
    chunk_index INT NOT NULL,
    tokens INT,
    vector_id VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    INDEX idx_knowledge_base_id (knowledge_base_id),
    INDEX idx_chunk_id (chunk_id),
    UNIQUE KEY uk_knowledge_chunk (knowledge_base_id, chunk_id)
);

-- 记忆表
CREATE TABLE IF NOT EXISTS memories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(100) NOT NULL,
    memory_key VARCHAR(255) NOT NULL,
    value TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_key (memory_key),
    UNIQUE KEY uk_session_key (session_id, memory_key)
);

-- 工具调用记录表
CREATE TABLE IF NOT EXISTS tool_calls (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT,
    session_id VARCHAR(100),
    tool_name VARCHAR(100) NOT NULL,
    tool_input TEXT,
    tool_output TEXT,
    status ENUM('SUCCESS', 'FAILED') DEFAULT 'SUCCESS',
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_session_id (session_id),
    INDEX idx_tool_name (tool_name),
    INDEX idx_status (status)
);

-- Token使用统计表
CREATE TABLE IF NOT EXISTS token_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(100),
    conversation_id BIGINT,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    cost DECIMAL(10, 6) DEFAULT 0.000000,
    date DATE DEFAULT CURRENT_DATE,
    INDEX idx_session_id (session_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_date (date),
    UNIQUE KEY uk_session_date (session_id, date)
);

-- 用户偏好设置表
CREATE TABLE IF NOT EXISTS user_preferences (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(100) NOT NULL,
    nickname VARCHAR(100),
    ai_name VARCHAR(100),
    personality ENUM('GENTLE', 'HUMOROUS', 'RATIONAL', 'ACTIVE', 'ELEGANT'),
    preferences JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    UNIQUE KEY uk_session_id (session_id)
);