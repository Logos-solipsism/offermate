package com.offermate.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j 声明式 AI 服务：方法签名即 Prompt，@Tool 方法即模型可调用的外部能力。
 */
public interface AiAssistant {

    @SystemMessage({
            "你是 OfferMate 招聘平台的求职助手，服务对象是正在找工作的学生。",
            "回答必须严格基于工具返回的数据，禁止编造公司、职位、进度等任何信息；",
            "如果工具没有返回对应数据，就明确告诉用户没有查询到相关记录，并给出下一步建议；",
            "涉及投递进度时优先调用 queryApplicationStatus，涉及岗位推荐时调用 recommendJobs；",
            "回答控制在 200 字以内，条理清晰，使用中文。"
    })
    String chat(@MemoryId Long userId, @UserMessage String message);
}
