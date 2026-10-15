package com.offermate.modules.resume.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offermate.common.context.UserContext;
import com.offermate.common.exception.BizException;
import com.offermate.modules.resume.entity.Resume;
import com.offermate.modules.resume.entity.ResumeFile;
import com.offermate.modules.resume.mapper.ResumeFileMapper;
import com.offermate.modules.resume.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 简历查询（上传状态 + 异步解析状态）。
 * 前端靠它回答两个问题：文件传到哪了、解析好了没有（解析没完成是不能投递的）。
 */
@Service
@RequiredArgsConstructor
public class ResumeQueryService {

    private final ResumeFileMapper resumeFileMapper;
    private final ResumeMapper resumeMapper;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> myResumes() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        List<ResumeFile> files = resumeFileMapper.selectList(Wrappers.<ResumeFile>lambdaQuery()
                .eq(ResumeFile::getUserId, userId)
                .orderByDesc(ResumeFile::getCreateTime)
                .last("limit 20"));
        if (files.isEmpty()) {
            return List.of();
        }

        // 一次把解析记录查回来，避免按文件逐条查（列表接口不要写 N+1）
        List<Long> fileIds = files.stream().map(ResumeFile::getId).toList();
        Map<Long, Resume> resumeByFileId = resumeMapper.selectList(Wrappers.<Resume>lambdaQuery()
                        .in(Resume::getFileId, fileIds))
                .stream()
                .collect(Collectors.toMap(Resume::getFileId, r -> r, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>(files.size());
        for (ResumeFile file : files) {
            Resume resume = resumeByFileId.get(file.getId());
            Map<String, Object> parsed = parseJson(resume == null ? null : resume.getParsedJson());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileId", file.getId());
            item.put("fileName", file.getFileName());
            item.put("size", file.getSize());
            item.put("status", file.getStatus());
            item.put("parseStatus", resolveParseStatus(file, resume));
            item.put("createTime", file.getCreateTime());
            item.put("name", parsed.get("name"));
            item.put("education", parsed.get("education"));
            item.put("school", parsed.get("school"));
            item.put("skills", parsed.get("skills"));
            result.add(item);
        }
        return result;
    }

    /**
     * 文件已合并、但解析记录还没落库，对用户来说就是「解析中」——
     * 这里把两个状态合成前端只需要看的一个字段。
     */
    private String resolveParseStatus(ResumeFile file, Resume resume) {
        if (resume != null && resume.getParseStatus() != null) {
            return resume.getParseStatus();
        }
        return ResumeFile.STATUS_DONE.equals(file.getStatus()) ? Resume.PARSE_PARSING : Resume.PARSE_PENDING;
    }

    /** 解析结果是历史数据，脏了就当作没有，不让一条坏 JSON 把整个列表打挂 */
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
