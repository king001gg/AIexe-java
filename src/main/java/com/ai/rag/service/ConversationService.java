package com.ai.rag.service;

import com.ai.rag.model.entity.Conversation;
import com.ai.rag.model.entity.Message;
import com.ai.rag.repository.ConversationRepository;
import com.ai.rag.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话与消息持久化服务
 *
 * Stage 1：把原本只写内存 {@code ChatMemory} 的会话/消息接通到 MySQL（conversations / messages 表）。
 * 每个方法使用独立事务，避免把事务横跨在耗时的大模型调用上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    /**
     * 获取或创建会话（按 sessionId 幂等）
     *
     * <p><b>为什么不用 {@code @Transactional}（缺陷 D4）：</b>并发首次访问同一个
     * sessionId 时，「查不到 → 各自插入」会产生重复会话，而重复会话会让返回
     * {@code Optional} 的 {@code findBySessionId} 永久抛
     * {@code IncorrectResultSizeDataAccessException}（见 V2 迁移的注释）。
     *
     * <p>修法是「撞唯一键后重查」。但**捕获必须发生在事务边界之外**：唯一键冲突会把当前
     * 事务标记成 rollback-only，在同一个事务里继续做任何操作——包括再查一次——都不会成功。
     * 所以这里去掉 {@code @Transactional}，让每次 repository 调用各自成事务，
     * {@code catch} 处就已经在失败的那个事务之外了。
     *
     * <p>{@code AgentService} / {@code StreamingChatService} 调用本方法时都不在事务里，
     * 因此去掉注解不改变事务语义（本方法也从来不是更大事务的一部分）。
     *
     * <p>{@code saveAndFlush} 而不是 {@code save}：让约束冲突**在这里**立刻抛出，
     * 而不是拖到事务提交时才炸——那时已经离开了这个 try 块，就没法重查了。
     */
    public Conversation getOrCreateConversation(String sessionId, String nickname, String model) {
        Conversation existing = conversationRepository.findBySessionId(sessionId).orElse(null);
        if (existing != null) {
            return existing;
        }

        Conversation created = new Conversation();
        created.setSessionId(sessionId);
        created.setUserName(nickname);
        created.setModel(model);

        try {
            return conversationRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            // 别的线程抢先插入了同 sessionId 的会话。此处已不在那个失败的事务里，可以安全重查。
            log.debug("会话 {} 已被并发创建，改为读取既有会话", sessionId);
            return conversationRepository.findBySessionId(sessionId).orElseThrow(() ->
                    new IllegalStateException("并发创建会话后仍查不到：" + sessionId, e));
        }
    }

    /**
     * 保存一条消息（按 sessionId 重新加载会话，避免使用游离实体）
     */
    @Transactional
    public Message saveMessage(String sessionId, Message.Role role, String content, Integer tokens) {
        Conversation conversation = conversationRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在：" + sessionId));

        Message message = new Message();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        message.setTokens(tokens);
        return messageRepository.save(message);
    }

    /**
     * 若标题仍为默认「新会话」，用首条消息摘要更新标题；返回最终标题
     */
    @Transactional
    public String updateTitleIfDefault(String sessionId, String title) {
        Conversation conversation = conversationRepository.findBySessionId(sessionId).orElse(null);
        if (conversation == null) {
            return title;
        }
        if ("新会话".equals(conversation.getTitle())) {
            conversation.setTitle(title);
            conversationRepository.save(conversation);
            return title;
        }
        return conversation.getTitle();
    }
}
