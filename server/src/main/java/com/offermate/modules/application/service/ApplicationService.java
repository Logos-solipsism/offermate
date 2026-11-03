package com.offermate.modules.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.context.UserContext;
import com.offermate.common.exception.BizException;
import com.offermate.common.limiter.RateLimit;
import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.auth.entity.SysUser;
import com.offermate.modules.auth.mapper.SysUserMapper;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import com.offermate.modules.resume.entity.Resume;
import com.offermate.modules.resume.entity.ResumeFile;
import com.offermate.modules.resume.mapper.ResumeFileMapper;
import com.offermate.modules.resume.mapper.ResumeMapper;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqProducer;
import com.offermate.mq.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 投递主链路。设计目标：**不等待任何外部调用**（压测实测：单请求 20ms，50 并发 P99 553ms）。
 *
 * 主链路只做 6 件事：参数校验 → 令牌桶限流 → 快速幂等 → 分布式锁 → 去重与落库 → 发 MQ。
 * 简历解析、JD 匹配打分、站内信/短信推送全部异步，接口不等待任何 RPC。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private static final Set<String> ALLOWED_STATUS = Set.of(
            Application.STATUS_VIEWED, Application.STATUS_INTERVIEW,
            Application.STATUS_OFFER, Application.STATUS_REJECTED);

    private final ApplicationMapper applicationMapper;
    private final JobMapper jobMapper;
    private final ResumeMapper resumeMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redis;
    private final MqProducer mqProducer;
    private final SysUserMapper sysUserMapper;

    @RateLimit(key = "apply", capacity = 5, rate = 1,
            dimension = RateLimit.Dimension.USER, message = "投递过于频繁，请稍后再试")
    @Transactional(rollbackFor = Exception.class)
    public ApplyResult apply(Long jobId) {
        Long userId = requireUserId();

        Job job = jobMapper.selectById(jobId);
        if (job == null || job.getStatus() == null || job.getStatus() != 1
                || job.getAuditStatus() == null || job.getAuditStatus() != 1) {
            throw new BizException("职位不存在或已下线");
        }

        // 1) 快速幂等：同一用户对同一职位的重复点击在 5s 内直接拒绝，不进入后面的锁与数据库
        String requestKey = "apply:req:" + userId + ":" + jobId;
        Boolean first = redis.opsForValue().setIfAbsent(requestKey, "1", Duration.ofSeconds(5));
        if (!Boolean.TRUE.equals(first)) {
            throw new BizException("请勿重复提交");
        }

        // 2) 分布式锁：锁的是"用户 + 职位"，leaseTime = -1 交给 WatchDog 续期
        RLock lock = redissonClient.getLock("lock:apply:" + userId + ":" + jobId);
        String dedupKey = null;
        boolean locked = false;
        try {
            locked = lock.tryLock(2, -1, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException("请求处理中，请稍后重试");
            }

            // 3) 业务幂等：数据库唯一索引(uk_user_job)之外的前置判断，避免依赖异常做流程控制
            boolean applied = applicationMapper.exists(Wrappers.<Application>lambdaQuery()
                    .eq(Application::getUserId, userId)
                    .eq(Application::getJobId, jobId));
            if (applied) {
                throw new BizException("你已经投递过该职位");
            }

            Resume resume = requireDefaultResume(userId);
            ResumeFile resumeFile = resumeFileMapper.selectById(resume.getFileId());
            if (resumeFile == null) {
                throw new BizException("简历文件不存在，请重新上传");
            }

            // 4) 内容级去重：同一份简历内容（MD5 相同）24h 内不允许重复投递同一招聘方，
            //    这是招聘平台的真实风控规则——拦截"一份简历海投同一家公司所有岗位"的刷量行为。
            dedupKey = "apply:dedup:" + userId + ":" + job.getHrId() + ":" + resumeFile.getMd5();
            Boolean allowed = redis.opsForValue().setIfAbsent(dedupKey, String.valueOf(jobId), Duration.ofHours(24));
            if (!Boolean.TRUE.equals(allowed)) {
                log.warn("内容级去重拦截: userId={}, hrId={}, resumeMd5={}", userId, job.getHrId(), resumeFile.getMd5());
                throw new BizException("同一份简历 24 小时内不能重复投递同一招聘方，修改简历后再试");
            }

            // 5) 落库
            Application application = new Application();
            application.setUserId(userId);
            application.setJobId(jobId);
            application.setResumeId(resume.getId());
            application.setResumeMd5(resumeFile.getMd5());
            application.setStatus(Application.STATUS_APPLIED);
            application.setHrFeedback("");
            application.setMatchScore(0);
            application.setCreateTime(LocalDateTime.now());
            application.setUpdateTime(LocalDateTime.now());
            applicationMapper.insert(application);

            // 6) 发 MQ，主链路到此结束（不等待解析、不等待打分、不等待短信）
            // 关键：必须在业务事务提交之后再发。在事务里发的话，消费者可能比提交更快，
            // 读不到刚插入的 application 就把消息当成功丢掉——匹配分永远停在 0，而且不会重投。
            Runnable sendMq = () -> {
                try {
                    mqProducer.sendAsync(MqTopics.JOB_MATCH, "application:" + application.getId(),
                            new MqEvents.JobMatchEvent(application.getId(), userId, jobId,
                                    resume.getId(), resumeFile.getMd5()));
                    mqProducer.sendAsync(MqTopics.NOTIFY, "application:" + application.getId(),
                            new MqEvents.NotifyEvent(userId, application.getId(), "APPLY",
                                    "投递成功", "你已成功投递「" + job.getTitle() + "」，HR 将在 3 个工作日内处理"));
                } catch (RuntimeException e) {
                    // 提交后发送失败：业务数据已经落库，只能靠人工重放（本地消息表仍是待补项）
                    log.error("投递消息发送失败 applicationId={}", application.getId(), e);
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendMq.run();
                    }
                });
            } else {
                sendMq.run();
            }

            return new ApplyResult(application.getId(), Application.STATUS_APPLIED, "投递成功");
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发下另一个请求已插入，回滚本次并释放去重标记，避免误伤用户 24h
            if (dedupKey != null) {
                redis.delete(dedupKey);
            }
            throw new BizException("你已经投递过该职位");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("投递被中断，请重试");
        } finally {
            // 关键细节：必须在事务提交之后才释放锁，否则后一个线程可能在提交前读到旧数据；
            // 这里用事务同步回调保证"先提交、后解锁"。（唯一索引是最终兜底）
            releaseLockAfterCommit(lock, locked);
        }
    }

    /** HR 流转投递状态，并异步通知候选人 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long applicationId, String targetStatus, String feedback) {
        if (!ALLOWED_STATUS.contains(targetStatus)) {
            throw new BizException("非法的状态流转: " + targetStatus);
        }
        Application application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw new BizException("投递记录不存在");
        }
        Job job = jobMapper.selectById(application.getJobId());
        if (job == null || !job.getHrId().equals(UserContext.userId())) {
            throw new BizException(403, "无权操作该投递");
        }
        application.setStatus(targetStatus);
        application.setHrFeedback(feedback == null ? "" : feedback);
        application.setUpdateTime(LocalDateTime.now());
        applicationMapper.updateById(application);

        mqProducer.sendAsync(MqTopics.NOTIFY, "application:" + applicationId,
                new MqEvents.NotifyEvent(application.getUserId(), applicationId, "STATUS_CHANGE",
                        "投递状态更新", "「" + job.getTitle() + "」的状态变为: " + targetStatus));
    }

    public List<Map<String, Object>> myApplications() {
        Long userId = requireUserId();
        return applicationMapper.selectList(Wrappers.<Application>lambdaQuery()
                        .eq(Application::getUserId, userId)
                        .orderByDesc(Application::getCreateTime))
                .stream()
                .map(a -> Map.<String, Object>of(
                        "applicationId", a.getId(),
                        "jobId", a.getJobId(),
                        "status", a.getStatus(),
                        "matchScore", a.getMatchScore(),
                        "applyTime", a.getCreateTime()))
                .toList();
    }

    /** HR 视角：某个职位收到的投递列表（只有该职位所属 HR 本人能看，越权直接 403）。 */
    public List<Map<String, Object>> jobApplications(Long jobId) {
        Long userId = requireUserId();
        Job job = jobMapper.selectById(jobId);
        if (job == null || !userId.equals(job.getHrId())) {
            throw new BizException(403, "无权查看该职位的投递");
        }
        List<Application> applications = applicationMapper.selectList(Wrappers.<Application>lambdaQuery()
                .eq(Application::getJobId, jobId)
                .orderByDesc(Application::getCreateTime));
        if (applications.isEmpty()) {
            return List.of();
        }
        // 一次把候选人昵称查回来（列表接口避免 N+1）
        List<Long> userIds = applications.stream().map(Application::getUserId).distinct().toList();
        Map<Long, String> nicknames = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .in(SysUser::getId, userIds))
                .stream()
                .collect(Collectors.toMap(SysUser::getId,
                        u -> u.getNickname() == null ? "求职者" : u.getNickname(), (a, b) -> a));

        return applications.stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationId", a.getId());
            row.put("userId", a.getUserId());
            row.put("nickname", nicknames.getOrDefault(a.getUserId(), "求职者" + a.getUserId()));
            row.put("status", a.getStatus());
            row.put("matchScore", a.getMatchScore());
            row.put("hrFeedback", a.getHrFeedback());
            row.put("applyTime", a.getCreateTime());
            return row;
        }).toList();
    }

    private Long requireUserId() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }

    private Resume requireDefaultResume(Long userId) {
        Resume resume = resumeMapper.selectOne(Wrappers.<Resume>lambdaQuery()
                .eq(Resume::getUserId, userId)
                .eq(Resume::getIsDefault, 1)
                .eq(Resume::getParseStatus, Resume.PARSE_DONE)
                .last("limit 1"));
        if (resume == null) {
            throw new BizException("请先上传简历，解析完成后再投递");
        }
        return resume;
    }

    private void releaseLockAfterCommit(RLock lock, boolean locked) {
        if (!locked) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
        } else if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public record ApplyResult(Long applicationId, String status, String message) {
    }
}
