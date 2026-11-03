package com.offermate.modules.agent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.common.exception.BizException;
import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import com.offermate.modules.job.service.JobQueryService;
import com.offermate.modules.resume.entity.Resume;
import com.offermate.modules.resume.mapper.ResumeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 只读查询的两条底线：
 * ① 简历只回摘要——文件地址、fileId、解析全文（姓名/学校/联系方式）绝不能出现在响应里；
 * ② userId 必填——Agent 没有用户登录态，不能靠上下文兜底猜身份。
 */
class AgentQueryServiceTest {

    private final JobQueryService jobQueryService = mock(JobQueryService.class);
    private final ApplicationMapper applicationMapper = mock(ApplicationMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final ResumeMapper resumeMapper = mock(ResumeMapper.class);

    private AgentQueryService service() {
        return new AgentQueryService(jobQueryService, applicationMapper, jobMapper, resumeMapper,
                new ObjectMapper());
    }

    private Resume resume(String parsedJson) {
        Resume resume = new Resume();
        resume.setId(7L);
        resume.setUserId(9003L);
        resume.setFileId(55L);
        resume.setTitle("后端简历");
        resume.setParseStatus(Resume.PARSE_DONE);
        resume.setParsedJson(parsedJson);
        return resume;
    }

    @Test
    @DisplayName("简历摘要只含 id/title/parseStatus/skills，不泄露 fileId 与解析全文")
    void resumeSummaryIsWhitelisted() {
        when(resumeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(resume("{\"name\":\"张三\",\"school\":\"某大学\","
                        + "\"skills\":[\"Java\",\"Redis\"],\"sourceObject\":\"resume/2024/a.pdf\"}")));

        List<Map<String, Object>> rows = service().resumes(9003L);

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(Set.of("id", "title", "parseStatus", "skills"), row.keySet());
        assertEquals(7L, row.get("id"));
        assertEquals(Resume.PARSE_DONE, row.get("parseStatus"));
        assertEquals(List.of("Java", "Redis"), row.get("skills"));
        assertFalse(row.toString().contains("某大学"));
        assertFalse(row.toString().contains("a.pdf"));
    }

    @Test
    @DisplayName("解析 JSON 是脏数据时只返回空 skills，不影响列表")
    void dirtyParsedJsonDegrades() {
        when(resumeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(resume("not-a-json")));

        List<Map<String, Object>> rows = service().resumes(9003L);

        assertEquals(List.of(), rows.get(0).get("skills"));
    }

    @Test
    @DisplayName("投递列表按 userId 过滤，keyword 匹配职位名")
    void applicationsFilteredByKeyword() {
        Application applied = new Application();
        applied.setId(1L);
        applied.setUserId(9003L);
        applied.setJobId(9001L);
        applied.setStatus(Application.STATUS_APPLIED);
        applied.setMatchScore(80);
        Application appliedFrontend = new Application();
        appliedFrontend.setId(2L);
        appliedFrontend.setUserId(9003L);
        appliedFrontend.setJobId(9004L);
        appliedFrontend.setStatus(Application.STATUS_VIEWED);
        when(applicationMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(applied, appliedFrontend));

        Job backend = new Job();
        backend.setId(9001L);
        backend.setTitle("后端开发工程师");
        backend.setCity("北京");
        Job frontend = new Job();
        frontend.setId(9004L);
        frontend.setTitle("前端开发工程师");
        when(jobMapper.selectBatchIds(any())).thenReturn(List.of(backend, frontend));

        assertEquals(1, service().applications(9003L, "后端").size());
        assertEquals(1, service().applications(9003L, "前端").size());
        assertTrue(service().applications(9003L, "不存在的岗位").isEmpty());
        // 关键词为空/空白 = 不过滤，两条都返回
        assertEquals(2, service().applications(9003L, "  ").size());
    }

    @Test
    @DisplayName("缺 userId 时直接拒绝，不查库")
    void userIdRequired() {
        assertThrows(BizException.class, () -> service().applications(null, null));
        assertThrows(BizException.class, () -> service().resumes(null));
    }
}
