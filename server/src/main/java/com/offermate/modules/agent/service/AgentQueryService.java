package com.offermate.modules.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.common.exception.BizException;
import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import com.offermate.modules.job.service.JobQueryService;
import com.offermate.modules.resume.entity.Resume;
import com.offermate.modules.resume.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 外部 Agent 的只读查询：全部走既有表结构与既有检索实现，不做任何写操作。
 *
 * <p>两条数据边界：
 * <ol>
 *   <li>userId 必须显式传入：Agent 没有用户登录态，不能靠 UserContext 猜身份；</li>
 *   <li>简历只回摘要（id/title/parseStatus/skills），文件地址与解析全文一律不出网关。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AgentQueryService {

    /** 单次返回上限：Agent 拿去做推理，不需要一次拉全库 */
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 10;

    private final JobQueryService jobQueryService;
    private final ApplicationMapper applicationMapper;
    private final JobMapper jobMapper;
    private final ResumeMapper resumeMapper;
    private final ObjectMapper objectMapper;

    /** 复用求职者侧检索：只返回"在招 + 审核通过"的职位，这条业务红线在 Agent 侧同样有效 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> jobs(String city, String keyword, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limit));
        Object rows = jobQueryService.list(keyword, city, 1, size).get("list");
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }

    /**
     * 按 userId 过滤投递，keyword 匹配所投职位的公司/职位称谓。
     *
     * <p>注意：库表里没有"公司"实体（sys_user 只有一个未被使用的 company_id），
     * 招聘方以 HR 账号呈现，所以关键词只能落在职位名上——与既有 AI 工具的口径一致。
     */
    public List<Map<String, Object>> applications(Long userId, String keyword) {
        if (userId == null) {
            throw new BizException("userId 不能为空");
        }
        List<Application> applications = applicationMapper.selectList(Wrappers.<Application>lambdaQuery()
                .eq(Application::getUserId, userId)
                .orderByDesc(Application::getCreateTime)
                .last("limit " + MAX_LIMIT));
        if (applications.isEmpty()) {
            return List.of();
        }

        // 一次把职位查回来，避免按投递逐条查（列表接口不要写 N+1）
        List<Long> jobIds = applications.stream().map(Application::getJobId).distinct().toList();
        Map<Long, Job> jobById = jobMapper.selectBatchIds(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity(), (a, b) -> a));

        String needle = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>(applications.size());
        for (Application application : applications) {
            Job job = jobById.get(application.getJobId());
            String title = job == null ? "" : job.getTitle();
            if (needle != null && !title.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationId", application.getId());
            row.put("userId", application.getUserId());
            row.put("jobId", application.getJobId());
            row.put("jobTitle", title);
            row.put("city", job == null ? null : job.getCity());
            row.put("status", application.getStatus());
            row.put("matchScore", application.getMatchScore());
            row.put("applyTime", application.getCreateTime());
            result.add(row);
        }
        return result;
    }

    /** 简历摘要：只吐 id/title/parseStatus/skills，fileId、对象存储路径、parsedJson 全文都不出网关 */
    public List<Map<String, Object>> resumes(Long userId) {
        if (userId == null) {
            throw new BizException("userId 不能为空");
        }
        return resumeMapper.selectList(Wrappers.<Resume>lambdaQuery()
                        .eq(Resume::getUserId, userId)
                        .orderByDesc(Resume::getCreateTime)
                        .last("limit " + MAX_LIMIT))
                .stream()
                .map(this::toResumeSummary)
                .toList();
    }

    /**
     * 白名单字段：id / title / parseStatus / skills。
     * parsedJson 里只挑出 skills 一项，fileId 与解析全文（可能含姓名、学校、联系方式）绝对不下发。
     */
    private Map<String, Object> toResumeSummary(Resume resume) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", resume.getId());
        summary.put("title", resume.getTitle());
        summary.put("parseStatus", resume.getParseStatus());
        summary.put("skills", extractSkills(resume.getParsedJson()));
        return summary;
    }

    /** 解析结果是历史数据，脏了就当作没有，不让一条坏 JSON 把整个列表打挂 */
    private List<String> extractSkills(String parsedJson) {
        if (parsedJson == null || parsedJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(parsedJson, new TypeReference<>() {
            });
            Object skills = parsed.get("skills");
            if (skills instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
