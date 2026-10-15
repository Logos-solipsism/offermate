package com.offermate.modules.bench;

import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.notification.entity.Notification;
import com.offermate.modules.notification.mapper.NotificationMapper;
import com.offermate.modules.resume.client.ResumeParserClient;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqProducer;
import com.offermate.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 压测对比专用（只在 --spring.profiles.active=bench 时生效，生产代码完全不受影响）。
 *
 * 目的：把"同一套业务、同步做 vs 异步做"的差异单独测出来。
 * 两个方法的差异只有一个：**要不要等外部调用**。
 *
 *   applySync  = 重构前的写法：落库 → 调三方解析(等待) → 写站内信(等待) → 返回
 *   applyAsync = 现在的写法：落库 → 发消息 → 立刻返回
 *
 * 为了只测"等待"这一个变量，bench 接口刻意不做限流、幂等和分布式锁
 * （那些是 Redis 往返，量级只有毫秒，不影响结论）。
 */
@Slf4j
@Service
@Profile("bench")
@RequiredArgsConstructor
public class BenchService {

    private final ApplicationMapper applicationMapper;
    private final NotificationMapper notificationMapper;
    private final ResumeParserClient resumeParserClient;
    private final MqProducer mqProducer;
    private final com.offermate.common.retry.ExponentialBackoffRetryer retryer;

    /** 异步路径：只落库 + 发消息，立刻返回 */
    public long applyAsync(Long userId, Long jobId) {
        long start = System.currentTimeMillis();
        Application application = insertApplication(userId, jobId);

        mqProducer.sendAsync(MqTopics.JOB_MATCH, "application:" + application.getId(),
                new MqEvents.JobMatchEvent(application.getId(), userId, jobId, 0L, application.getResumeMd5()));
        mqProducer.sendAsync(MqTopics.NOTIFY, "application:" + application.getId(),
                new MqEvents.NotifyEvent(userId, application.getId(), "APPLY", "投递成功", "你已成功投递该职位"));
        return System.currentTimeMillis() - start;
    }

    /** 同步路径：落库 + 等三方解析 + 等写站内信，才返回 */
    public long applySync(Long userId, Long jobId) {
        long start = System.currentTimeMillis();
        Application application = insertApplication(userId, jobId);

        // ① 等三方解析（Mock 默认 800ms，模拟真实的外部服务耗时）
        resumeParserClient.parse("bench/resume/" + jobId);

        // ② 等站内信落库（异步版里这一步在消费者里做）
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType("APPLY");
        notification.setTitle("投递成功");
        notification.setContent("你已成功投递该职位");
        notification.setBizId(application.getId());
        notification.setReadFlag(0);
        notification.setCreateTime(LocalDateTime.now());
        notificationMapper.insert(notification);

        return System.currentTimeMillis() - start;
    }

    /**
     * 压测用：在给定的「失败率注入」下，跑 N 次真实的指数退避重试，统计最终成功率。
     * 目的是把「重试机制到底能救回多少失败」变成可测量的数字，而不是靠估。
     */
    public java.util.Map<String, Object> retryExperiment(double failRate, int count) {
        int firstTryOk = 0;
        int finalOk = 0;
        for (int i = 0; i < count; i++) {
            final boolean[] isFirst = {true};
            final boolean[] firstResult = {false};
            try {
                retryer.execute("故障注入实验", () -> {
                    boolean ok = java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= failRate;
                    if (isFirst[0]) { firstResult[0] = ok; isFirst[0] = false; }
                    if (!ok) { throw new IllegalStateException("injected failure"); }
                    return null;
                });
                finalOk++;
            } catch (Exception ignored) {
                // 重试用尽仍失败
            }
            if (firstResult[0]) { firstTryOk++; }
        }
        return java.util.Map.of(
                "count", count,
                "injectedFailRate", failRate,
                "singleAttemptSuccess", firstTryOk,
                "finalSuccess", finalOk,
                "finalFailed", count - finalOk,
                "singleAttemptRate", String.format("%.1f%%", firstTryOk * 100.0 / count),
                "finalSuccessRate", String.format("%.2f%%", finalOk * 100.0 / count));
    }

    private Application insertApplication(Long userId, Long jobId) {
        Application application = new Application();
        application.setUserId(userId);
        application.setJobId(jobId);
        application.setResumeId(0L);
        application.setResumeMd5("bench" + userId + "-" + jobId);
        application.setStatus(Application.STATUS_APPLIED);
        application.setHrFeedback("");
        application.setMatchScore(0);
        application.setCreateTime(LocalDateTime.now());
        application.setUpdateTime(LocalDateTime.now());
        applicationMapper.insert(application);
        return application;
    }
}
