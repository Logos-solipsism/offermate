package com.offermate.modules.job.controller;

import com.offermate.common.result.R;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.service.JobQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "职位")
@RestController
@RequestMapping("/api/job")
@RequiredArgsConstructor
public class JobController {

    private final JobQueryService jobQueryService;

    @Operation(summary = "职位检索（只返回在招且审核通过的职位）")
    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String city,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        return R.ok(jobQueryService.list(keyword, city, page, size));
    }

    @Operation(summary = "HR：我发布的职位")
    @GetMapping("/mine")
    public R<List<Job>> mine() {
        return R.ok(jobQueryService.myPublished());
    }

    @Operation(summary = "职位详情")
    @GetMapping("/{id}")
    public R<Job> detail(@PathVariable Long id) {
        return R.ok(jobQueryService.detail(id));
    }
}
