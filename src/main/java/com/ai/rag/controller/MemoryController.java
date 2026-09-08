package com.ai.rag.controller;

import com.ai.rag.model.dto.MemoryRequest;
import com.ai.rag.model.entity.Memory;
import com.ai.rag.repository.MemoryRepository;
import com.ai.rag.service.CacheService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryRepository memoryRepository;
    private final CacheService cacheService;

    /**
     * 保存记忆
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveMemory(@Valid @RequestBody MemoryRequest request) {
        log.info("Saving memory for session: {}", request.getSessionId());

        try {
            // 检查是否已存在
            Optional<Memory> existing = memoryRepository.findBySessionIdAndKey(
                request.getSessionId(),
                request.getKey()
            );

            Memory memory;
            if (existing.isPresent()) {
                memory = existing.get();
                memory.setValue(request.getValue());
            } else {
                memory = new Memory();
                memory.setSessionId(request.getSessionId());
                memory.setKey(request.getKey());
                memory.setValue(request.getValue());
            }

            memory = memoryRepository.save(memory);

            // 更新缓存
            cacheService.setMemoryToCache(request.getSessionId(), request.getKey(), request.getValue());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("memoryId", memory.getId());
            response.put("message", "记忆保存成功");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error saving memory", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "记忆保存失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取记忆
     */
    @GetMapping("/{sessionId}/{key}")
    public ResponseEntity<Map<String, Object>> getMemory(
            @PathVariable String sessionId,
            @PathVariable String key) {
        log.info("Getting memory for session: {}, key: {}", sessionId, key);

        // 先从缓存获取
        Optional<String> cachedValue = cacheService.getMemoryFromCache(sessionId, key);
        if (cachedValue.isPresent()) {
            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", sessionId);
            response.put("key", key);
            response.put("value", cachedValue.get());
            response.put("fromCache", true);
            return ResponseEntity.ok(response);
        }

        // 从数据库获取
        Optional<Memory> memory = memoryRepository.findBySessionIdAndKey(sessionId, key);
        if (memory.isPresent()) {
            // 更新缓存
            cacheService.setMemoryToCache(sessionId, key, memory.get().getValue());

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", sessionId);
            response.put("key", key);
            response.put("value", memory.get().getValue());
            response.put("fromCache", false);
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.notFound().build();
    }

    /**
     * 获取会话所有记忆
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<List<Memory>> getAllMemories(@PathVariable String sessionId) {
        log.info("Getting all memories for session: {}", sessionId);
        List<Memory> memories = cacheService.getMemories(sessionId);
        return ResponseEntity.ok(memories);
    }

    /**
     * 删除记忆
     */
    @DeleteMapping("/{sessionId}/{key}")
    public ResponseEntity<Map<String, Object>> deleteMemory(
            @PathVariable String sessionId,
            @PathVariable String key) {
        log.info("Deleting memory for session: {}, key: {}", sessionId, key);

        try {
            // 删除缓存
            cacheService.deleteMemoryFromCache(sessionId, key);

            // 删除数据库
            memoryRepository.deleteBySessionIdAndKey(sessionId, key);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "记忆删除成功");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error deleting memory", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "记忆删除失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 清除会话所有记忆
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearMemories(@PathVariable String sessionId) {
        log.info("Clearing all memories for session: {}", sessionId);

        try {
            // 清除缓存
            cacheService.clearSessionCache(sessionId);

            // 删除数据库
            memoryRepository.deleteBySessionId(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "会话记忆已清除");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error clearing memories", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "记忆清除失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}