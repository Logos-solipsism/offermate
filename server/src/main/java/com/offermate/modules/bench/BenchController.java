package com.offermate.modules.bench;

import com.offermate.common.context.UserContext;
import com.offermate.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 压测对比接口，仅 bench profile 生效。 */
@RestController
@Profile("bench")
@RequestMapping("/api/bench")
@RequiredArgsConstructor
public class BenchController {

    private final BenchService benchService;

    @PostMapping("/apply-async/{jobId}")
    public R<Map<String, Object>> applyAsync(@PathVariable Long jobId) {
        long cost = benchService.applyAsync(UserContext.userId(), jobId);
        return R.ok(Map.of("mode", "async", "serverCostMillis", cost));
    }

    /** 重试效果实验：failRate=0.3 表示注入 30% 失败率 */
    @PostMapping("/retry-experiment")
    public R<Map<String, Object>> retryExperiment(@RequestParam double failRate,
                                                  @RequestParam(defaultValue = "200") int count) {
        return R.ok(benchService.retryExperiment(failRate, count));
    }

    @PostMapping("/apply-sync/{jobId}")
    public R<Map<String, Object>> applySync(@PathVariable Long jobId) {
        long cost = benchService.applySync(UserContext.userId(), jobId);
        return R.ok(Map.of("mode", "sync", "serverCostMillis", cost));
    }
}
