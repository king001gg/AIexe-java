package com.ai.rag.repository;

import com.ai.rag.model.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户偏好设置数据访问层
 */
@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    /**
     * 根据会话ID查找用户偏好设置
     */
    Optional<UserPreference> findBySessionId(String sessionId);

    /**
     * 根据会话ID和AI名称查找用户偏好设置
     */
    Optional<UserPreference> findBySessionIdAndAiName(String sessionId, String aiName);

    /**
     * 根据性格查找用户偏好设置
     */
    List<UserPreference> findByPersonality(UserPreference.Personality personality);

    /**
     * 检查会话是否已有偏好设置
     */
    boolean existsBySessionId(String sessionId);

    /**
     * 更新用户昵称
     */
    @Query("UPDATE UserPreference up SET up.nickname = :nickname WHERE up.sessionId = :sessionId")
    void updateNickname(@Param("sessionId") String sessionId, @Param("nickname") String nickname);

    /**
     * 更新AI名称
     */
    @Query("UPDATE UserPreference up SET up.aiName = :aiName WHERE up.sessionId = :sessionId")
    void updateAiName(@Param("sessionId") String sessionId, @Param("aiName") String aiName);

    /**
     * 更新性格设置
     */
    @Query("UPDATE UserPreference up SET up.personality = :personality WHERE up.sessionId = :sessionId")
    void updatePersonality(@Param("sessionId") String sessionId, @Param("personality") UserPreference.Personality personality);

    /**
     * 更新偏好设置
     */
    @Query("UPDATE UserPreference up SET up.preferences = :preferences WHERE up.sessionId = :sessionId")
    void updatePreferences(@Param("sessionId") String sessionId, @Param("preferences") java.util.Map<String, Object> preferences);
}