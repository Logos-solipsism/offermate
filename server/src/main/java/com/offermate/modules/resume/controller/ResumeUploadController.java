package com.offermate.modules.resume.controller;

import com.offermate.common.context.UserContext;
import com.offermate.common.result.R;
import com.offermate.modules.resume.service.ResumeUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Tag(name = "简历上传")
@Validated
@RestController
@RequestMapping("/api/resume/upload")
@RequiredArgsConstructor
public class ResumeUploadController {

    private final ResumeUploadService resumeUploadService;

    @Operation(summary = "初始化上传：秒传命中 / 断点续传探测 / 新建分片任务")
    @PostMapping("/init")
    public R<ResumeUploadService.InitResult> init(@RequestBody @Validated InitRequest request) {
        return R.ok(resumeUploadService.init(
                UserContext.userId(),
                request.fileName(),
                request.fileMd5(),
                request.size()));
    }

    @Operation(summary = "上传分片（5MB/片，最后一片可小于 5MB）")
    @PostMapping("/chunk")
    public R<ResumeUploadService.ChunkResult> chunk(@RequestParam String uploadId,
                                                    @RequestParam int partNumber,
                                                    @RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return R.ok(resumeUploadService.uploadChunk(
                    UserContext.userId(), uploadId, partNumber, file.getSize(), in));
        }
    }

    @Operation(summary = "合并分片：Redisson 加锁 + 触发 MQ 异步解析")
    @PostMapping("/merge")
    public R<ResumeUploadService.MergeResult> merge(@RequestBody @Validated MergeRequest request) {
        return R.ok(resumeUploadService.merge(
                UserContext.userId(), request.uploadId(), request.totalParts()));
    }

    public record InitRequest(@NotBlank String fileName, @NotBlank String fileMd5, long size) {
    }

    public record MergeRequest(@NotBlank String uploadId, int totalParts) {
    }
}
