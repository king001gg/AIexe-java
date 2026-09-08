package com.ai.rag.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户偏好设置实体类
 */
@Entity
@Table(name = "user_preferences", uniqueConstraints = {
    @UniqueConstraint(name = "uk_session_id", columnNames = {"session_id"})
})
@Data
@NoArgsConstructor
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String sessionId;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "ai_name", length = 100)
    private String aiName;

    @Enumerated(EnumType.STRING)
    @Column(name = "personality", length = 20)
    private Personality personality;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences", columnDefinition = "JSON")
    private Map<String, Object> preferences;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 性格枚举
     */
    public enum Personality {
        GENTLE("温柔体贴"),
        HUMOROUS("幽默风趣"),
        RATIONAL("理性冷静"),
        ACTIVE("活泼可爱"),
        ELEGANT("知性优雅");

        private final String description;

        Personality(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}