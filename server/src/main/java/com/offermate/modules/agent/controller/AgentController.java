package com.offermate.modules.agent.controller;

import com.offermate.common.result.R;
import com.offermate.modules.agent.service.AgentQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 给外部 Python Agent 服务调用的只读接口。
 *
 * <p>全部 GET、全部只读、全部沿用 {@link R} 统一响应；
 * 鉴权由 {@code AgentAuthInterceptor} 在 /api/agent/** 上统一把守，不经过用户 JWT 拦截器。
 */
@Tag(name = "Agent 只读接口")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentQueryService agentQueryService;

    @Operation(summary = "健康检查（验证服务与 token 是否打通）")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of("ok", true));
    }

    @Operation(summary = "职位检索：只看在招且审核通过的职位")
    @GetMapping("/jobs")
    public R<List<Map<String, Object>>> jobs(@RequestParam(required = false) String city,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer limit) {
        return R.ok(agentQueryService.jobs(city, keyword, limit));
    }

    @Operation(summary = "投递记录：按 userId 过滤，keyword 匹配职位名称")
    @GetMapping("/applications")
    public R<List<Map<String, Object>>> applications(@RequestParam Long userId,
                                                     @RequestParam(required = false) String keyword) {
        return R.ok(agentQueryService.applications(userId, keyword));
    }

    @Operation(summary = "简历摘要：不含文件地址与解析全文")
    @GetMapping("/resumes")
    public R<List<Map<String, Object>>> resumes(@RequestParam Long userId) {
        return R.ok(agentQueryService.resumes(userId));
    }
}
