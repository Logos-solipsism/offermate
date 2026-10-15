package com.offermate.modules.resume.controller;

import com.offermate.common.result.R;
import com.offermate.modules.resume.service.ResumeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "简历查询")
@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeQueryService resumeQueryService;

    @Operation(summary = "我上传的简历：上传状态 + 异步解析状态 + 解析出的结构化字段")
    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        return R.ok(resumeQueryService.myResumes());
    }
}
