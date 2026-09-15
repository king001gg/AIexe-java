package com.ai.rag.service;

import com.ai.rag.model.entity.Conversation;
import com.ai.rag.model.entity.Message;
import com.ai.rag.repository.ConversationRepository;
import com.ai.rag.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     */
    @Transactional
    public Conversation getOrCreateConversation(String sessionId, String nickname, String model) {
        return conversationRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    Conversation conversation = new Conversation();
                    conversation.setSessionId(sessionId);
                    conversation.setUserName(nickname);
                    conversation.setModel(model);
                    return conversationRepository.save(conversation);
                });
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
