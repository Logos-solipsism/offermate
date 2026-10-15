package com.offermate.modules.application.controller;

import com.offermate.common.result.R;
import com.offermate.modules.application.service.ApplicationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/application")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping
    public R<ApplicationService.ApplyResult> apply(@RequestBody @Validated ApplyRequest request) {
        return R.ok(applicationService.apply(request.jobId()));
    }

    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        return R.ok(applicationService.myApplications());
    }

    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody @Validated StatusRequest request) {
        applicationService.updateStatus(id, request.status(), request.feedback());
        return R.ok();
    }

    @GetMapping("/job/{jobId}")
    public R<List<Map<String, Object>>> byJob(@PathVariable Long jobId) {
        return R.ok(applicationService.jobApplications(jobId));
    }

    public record ApplyRequest(@NotNull Long jobId) {
    }

    public record StatusRequest(@NotBlank String status, String feedback) {
    }
}
