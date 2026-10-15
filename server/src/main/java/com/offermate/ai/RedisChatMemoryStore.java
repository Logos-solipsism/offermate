package com.offermate.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 Redis 承载大模型的多轮会话记忆（默认窗口 20 条、TTL 7 天）。
 * 为什么不用内存：服务水平扩展 + 会重启，内存记忆会导致"换个实例就失忆"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "ai:memory:";

    private final StringRedisTemplate redis;

    @Value("${ai.memory-ttl-days:7}")
    private long ttlDays;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redis.opsForValue().get(key(memoryId));
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
        } catch (Exception e) {
            log.warn("会话记忆反序列化失败，按空历史处理 memoryId={}", memoryId);
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        redis.opsForValue().set(key(memoryId), json, Duration.ofDays(ttlDays));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
