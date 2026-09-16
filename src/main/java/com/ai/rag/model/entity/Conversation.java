package com.ai.rag.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话实体类
 *
 * <p>{@code session_id} 的唯一约束（缺陷 D4）：原先只有普通索引，而
 * {@code getOrCreateConversation} 是先查后插，并发首次访问会插入多行；多行一旦存在，
 * 返回 {@code Optional} 的 {@code findBySessionId} 就会抛
 * {@code IncorrectResultSizeDataAccessException}，该 sessionId 之后永久 500。
 * 约束由 {@code V2__dedupe_conversations_and_add_unique_session.sql} 建立
 * （那里先合并存量重复行再加约束）。{@code ddl-auto: none} 下这里的声明不产生 DDL，
 * 只用于与 {@link com.ai.rag.model.entity.TokenUsage} 等的写法对齐、并表达意图。
 */
@Entity
@Table(name = "conversations", uniqueConstraints = {
    @UniqueConstraint(name = "uk_conversations_session_id", columnNames = {"session_id"})
})
@Data
@NoArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String sessionId;

    @Column(length = 255)
    private String title;

    @Column(length = 100)
    private String userName;

    @Column(length = 50)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Message> messages;

    @PrePersist
    protected void onCreate() {
        if (title == null) {
            title = "新会话";
        }
    }
}