package com.offermate.modules.agent.controller;

import com.offermate.modules.agent.auth.AgentAuthInterceptor;
import com.offermate.modules.agent.service.AgentQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端（MockMvc + 真实拦截器）：钉住"不带 token 进不去、带对了才拿到 R 结构"，
 * 以及 Agent 链路完全不依赖用户 JWT。
 */
class AgentControllerTest {

    private static final String TOKEN = "agent-service-token-for-test-0123456789";

    private MockMvc mvc(String serviceToken) {
        AgentAuthInterceptor interceptor = new AgentAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "serviceToken", serviceToken);
        return MockMvcBuilders
                .standaloneSetup(new AgentController(mock(AgentQueryService.class)))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    @DisplayName("带正确 X-Agent-Token 时 /api/agent/health 返回 R<{ok:true}>")
    void healthWithToken() throws Exception {
        mvc(TOKEN).perform(get("/api/agent/health").header(AgentAuthInterceptor.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true));
    }

    @Test
    @DisplayName("不带 token 时 /api/agent/health 直接 401，拿不到任何数据")
    void healthWithoutToken() throws Exception {
        mvc(TOKEN).perform(get("/api/agent/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token 配置为空时即便请求带了正确的头也 401（fail-closed）")
    void healthWhenTokenUnconfigured() throws Exception {
        mvc("").perform(get("/api/agent/health").header(AgentAuthInterceptor.TOKEN_HEADER, TOKEN))
                .andExpect(status().isUnauthorized());
    }
}
