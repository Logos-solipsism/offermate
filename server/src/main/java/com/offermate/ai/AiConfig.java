package com.offermate.ai;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 装配：OpenAI 兼容协议接入（硅基流动 / DeepSeek / 通义 / Ollama 均可，只改 base-url 与 model）。
 * 三个关键点：
 *   1) tools(assistantTools) → 开启 Function Calling；
 *   2) chatMemoryProvider + RedisChatMemoryStore → 多轮会话记忆落 Redis；
 *   3) maxMessages 窗口 20 条 → 控制 Token 成本。
 */
@Configuration
public class AiConfig {

    @Value("${ai.base-url}")
    private String baseUrl;

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.model}")
    private String modelName;

    @Value("${ai.temperature:0.3}")
    private Double temperature;

    @Value("${ai.timeout-seconds:30}")
    private Long timeoutSeconds;

    @Value("${ai.memory-window:20}")
    private Integer memoryWindow;

    @Bean
    public OpenAiChatModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public AiAssistant aiAssistant(OpenAiChatModel chatLanguageModel,
                                   AssistantTools assistantTools,
                                   RedisChatMemoryStore memoryStore) {
        return AiServices.builder(AiAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(assistantTools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(memoryWindow)
                        .chatMemoryStore(memoryStore)
                        .build())
                .build();
    }
}
