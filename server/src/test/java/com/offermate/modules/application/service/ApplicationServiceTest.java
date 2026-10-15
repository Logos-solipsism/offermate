package com.offermate.modules.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.offermate.common.context.UserContext;
import com.offermate.common.exception.BizException;
import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.auth.entity.SysUser;
import com.offermate.modules.auth.mapper.SysUserMapper;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import com.offermate.modules.resume.mapper.ResumeFileMapper;
import com.offermate.modules.resume.mapper.ResumeMapper;
import com.offermate.mq.MqProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HR 侧查询投递列表的归属校验：
 * 只有该职位所属 HR 本人能看，其他人（包括其他 HR）必须被挡在 403。
 */
class ApplicationServiceTest {

    private final ApplicationMapper applicationMapper = mock(ApplicationMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);

    private ApplicationService newService() {
        return new ApplicationService(
                applicationMapper,
                jobMapper,
                mock(ResumeMapper.class),
                mock(ResumeFileMapper.class),
                mock(RedissonClient.class),
                mock(StringRedisTemplate.class),
                mock(MqProducer.class),
                sysUserMapper);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private Job jobOf(Long id, Long hrId) {
        Job job = new Job();
        job.setId(id);
        job.setHrId(hrId);
        job.setTitle("后端开发工程师");
        return job;
    }

    @Test
    @DisplayName("未登录时查询投递列表返回 401")
    void notLoggedIn() {
        BizException error = assertThrows(BizException.class, () -> newService().jobApplications(9001L));
        assertEquals(401, error.getCode());
    }

    @Test
    @DisplayName("职位不存在时返回 403")
    void jobNotFound() {
        UserContext.set(new UserContext.LoginUser(9001L, "HR", "127.0.0.1"));
        when(jobMapper.selectById(9001L)).thenReturn(null);
        BizException error = assertThrows(BizException.class, () -> newService().jobApplications(9001L));
        assertEquals(403, error.getCode());
    }

    @Test
    @DisplayName("不是该职位的 HR 时返回 403（越权被挡）")
    void notTheOwnerHr() {
        UserContext.set(new UserContext.LoginUser(9002L, "HR", "127.0.0.1"));
        when(jobMapper.selectById(9001L)).thenReturn(jobOf(9001L, 9001L));
        BizException error = assertThrows(BizException.class, () -> newService().jobApplications(9001L));
        assertEquals(403, error.getCode());
    }

    @Test
    @DisplayName("职位所属 HR 查询时返回候选人列表（含昵称与匹配分）")
    void ownerSeesCandidates() {
        UserContext.set(new UserContext.LoginUser(9001L, "HR", "127.0.0.1"));
        when(jobMapper.selectById(9001L)).thenReturn(jobOf(9001L, 9001L));

        Application application = new Application();
        application.setId(1001L);
        application.setUserId(9003L);
        application.setJobId(9001L);
        application.setStatus(Application.STATUS_APPLIED);
        application.setMatchScore(75);
        application.setHrFeedback("");
        application.setCreateTime(LocalDateTime.now());
        when(applicationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(application));

        SysUser seeker = new SysUser();
        seeker.setId(9003L);
        seeker.setNickname("小明");
        when(sysUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of(seeker));

        List<Map<String, Object>> rows = newService().jobApplications(9001L);

        assertEquals(1, rows.size());
        assertEquals(1001L, rows.get(0).get("applicationId"));
        assertEquals("小明", rows.get(0).get("nickname"));
        assertEquals(75, rows.get(0).get("matchScore"));
    }
}
