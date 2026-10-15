package com.offermate.ai.controller;

import com.offermate.ai.AiAssistant;
import com.offermate.ai.RedisChatMemoryStore;
import com.offermate.ai.entity.AiChatLog;
import com.offermate.ai.mapper.AiChatLogMapper;
import com.offermate.common.context.UserContext;
import com.offermate.common.limiter.RateLimit;
import com.offermate.common.result.R;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * AI 助手接口。
 * 限流在这里的作用最直观：每一次对话都是一次真金白银的 Token 消耗，
 * 所以按用户维度限流（默认桶容量 10、每秒补 2 个令牌），防止被刷。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiAssistant aiAssistant;
    private final RedisChatMemoryStore memoryStore;
    private final AiChatLogMapper aiChatLogMapper;

    @RateLimit(key = "ai_chat", capacity = 10, rate = 2,
            dimension = RateLimit.Dimension.USER, message = "提问太快啦，休息一下再问")
    @PostMapping("/chat")
    public R<String> chat(@RequestBody @Validated ChatRequest request) {
        Long userId = UserContext.userId();
        long start = System.currentTimeMillis();
        String answer;
        try {
            answer = aiAssistant.chat(userId, request.message());
        } catch (Exception e) {
            log.error("AI 对话失败 userId={}", userId, e);
            answer = "助手暂时不可用，请稍后再试。";
        }
        log.info("AI 对话完成 userId={}, cost={}ms", userId, System.currentTimeMillis() - start);

        AiChatLog chatLog = new AiChatLog();
        chatLog.setUserId(userId);
        chatLog.setQuestion(request.message());
        chatLog.setAnswer(answer);
        chatLog.setToolCalls("");
        chatLog.setTokenCost(0);
        chatLog.setCreateTime(LocalDateTime.now());
        aiChatLogMapper.insert(chatLog);

        return R.ok(answer);
    }

    /** 清空会话记忆（隐私清理 / 换求职方向后重新开始） */
    @DeleteMapping("/memory")
    public R<Void> clearMemory() {
        memoryStore.deleteMessages(UserContext.userId());
        return R.ok();
    }

    public record ChatRequest(@NotBlank String message) {
    }
}
