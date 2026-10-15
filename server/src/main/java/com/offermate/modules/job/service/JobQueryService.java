package com.offermate.modules.job.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.context.UserContext;
import com.offermate.common.exception.BizException;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职位检索（求职者侧）。
 * 只返回「在招 + 审核通过」的职位——未审核的职位绝不能出现在求职者视野里，这是业务红线。
 */
@Service
@RequiredArgsConstructor
public class JobQueryService {

    /** 单页上限，防止前端传个超大 size 把库拖垮（分页参数永远要设上限） */
    private static final int MAX_PAGE_SIZE = 50;

    private final JobMapper jobMapper;

    /** 分页 + 关键词 + 城市检索。分页用 limit offset 手写，避免为一次查询引入分页插件。 */
    public Map<String, Object> list(String keyword, String city, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        var query = Wrappers.<Job>lambdaQuery()
                .eq(Job::getStatus, 1)
                .eq(Job::getAuditStatus, 1)
                // 传了才加条件：city / keyword 为空时不拼进 SQL
                .eq(city != null && !city.isBlank(), Job::getCity, city)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(Job::getTitle, keyword).or().like(Job::getSkills, keyword))
                .orderByDesc(Job::getCreateTime);

        Long total = jobMapper.selectCount(query);
        List<Job> rows = jobMapper.selectList(query.last("limit " + ((safePage - 1) * safeSize) + "," + safeSize));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0L : total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("list", rows.stream().map(JobQueryService::toCard).toList());
        return result;
    }

    public Job detail(Long jobId) {
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new BizException("职位不存在或已下线");
        }
        // 浏览量 +1：只更新这一个字段，避免整行覆盖
        Job counter = new Job();
        counter.setId(job.getId());
        counter.setViewCount((job.getViewCount() == null ? 0 : job.getViewCount()) + 1);
        jobMapper.updateById(counter);
        job.setViewCount(counter.getViewCount());
        return job;
    }

    /** HR 视角：我发布的职位（包含未审核 / 已下线的，HR 要能看到自己的全部职位） */
    public List<Job> myPublished() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return jobMapper.selectList(Wrappers.<Job>lambdaQuery()
                .eq(Job::getHrId, userId)
                .orderByDesc(Job::getCreateTime));
    }

    /** 列表只吐出卡片需要的字段，不把 jd 正文（可能几 KB）带进列表响应 */
    private static Map<String, Object> toCard(Job job) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", job.getId());
        card.put("title", job.getTitle());
        card.put("city", job.getCity());
        card.put("salaryMin", job.getSalaryMin());
        card.put("salaryMax", job.getSalaryMax());
        card.put("skills", job.getSkills());
        card.put("headCount", job.getHeadCount());
        card.put("hrId", job.getHrId());
        card.put("createTime", job.getCreateTime());
        return card;
    }
}
