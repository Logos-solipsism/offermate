package com.offermate.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.context.UserContext;
import com.offermate.modules.application.entity.Application;
import com.offermate.modules.application.mapper.ApplicationMapper;
import com.offermate.modules.job.entity.Job;
import com.offermate.modules.job.mapper.JobMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Function Calling 的工具集：把内部能力暴露给大模型。
 *
 * 安全要点：
 * 1) 所有查询都以 UserContext.userId() 作为数据边界，模型无法越权查别人的投递；
 * 2) 工具返回的是"事实文本"，模型只负责组织语言，不做数据计算，避免幻觉；
 * 3) 工具方法自身不做写操作，AI 只读不写，降低风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantTools {

    private final ApplicationMapper applicationMapper;
    private final JobMapper jobMapper;

    @Tool("查询当前登录用户投递过的职位及进度。keyword 可传公司名或职位关键词，传空字符串表示查询全部投递。")
    public String queryApplicationStatus(@P("公司名或职位关键词，可为空") String keyword) {
        Long userId = UserContext.userId();
        if (userId == null) {
            return "用户未登录，无法查询。";
        }
        List<Application> applications = applicationMapper.selectList(Wrappers.<Application>lambdaQuery()
                .eq(Application::getUserId, userId)
                .orderByDesc(Application::getCreateTime)
                .last("limit 10"));
        if (applications.isEmpty()) {
            return "该用户还没有任何投递记录。";
        }
        StringBuilder sb = new StringBuilder("投递记录如下（状态含义：APPLIED 已投递 / VIEWED 已查看 / INTERVIEW 面试邀约 / OFFER 已录用 / REJECTED 不合适）：\n");
        for (Application application : applications) {
            Job job = jobMapper.selectById(application.getJobId());
            String title = job == null ? ("职位" + application.getJobId()) : job.getTitle();
            String city = job == null ? "" : job.getCity();
            if (keyword != null && !keyword.isBlank() && !title.contains(keyword) && !city.contains(keyword)) {
                continue;
            }
            sb.append("- ").append(title).append("（").append(city).append("）")
                    .append(" 状态: ").append(application.getStatus())
                    .append(" 匹配分: ").append(application.getMatchScore())
                    .append(" 投递时间: ").append(application.getCreateTime())
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool("根据期望城市、最低月薪和技能关键词，推荐平台上正在招聘的职位，最多返回 5 条。")
    public String recommendJobs(@P("期望城市，可为空") String city,
                                @P("最低月薪，单位 K，可为空") Integer salaryMin,
                                @P("技能关键词，可为空") String skill) {
        List<Job> jobs = jobMapper.selectList(Wrappers.<Job>lambdaQuery()
                .eq(Job::getStatus, 1)
                .eq(Job::getAuditStatus, 1)
                .eq(city != null && !city.isBlank(), Job::getCity, city)
                .ge(salaryMin != null, Job::getSalaryMax, salaryMin)
                .like(skill != null && !skill.isBlank(), Job::getSkills, skill)
                .orderByDesc(Job::getCreateTime)
                .last("limit 5"));
        if (jobs.isEmpty()) {
            return "没有找到符合条件的在招职位，可以放宽城市或薪资条件。";
        }
        StringBuilder sb = new StringBuilder("为你推荐以下在招职位：\n");
        for (Job job : jobs) {
            sb.append("- ").append(job.getTitle()).append("｜").append(job.getCity())
                    .append("｜").append(job.getSalaryMin()).append("-").append(job.getSalaryMax()).append("K")
                    .append("｜技能要求: ").append(job.getSkills())
                    .append("｜职位ID: ").append(job.getId())
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool("根据职位ID查询职位详情，包括薪资、技能要求、职位描述和招聘人数。")
    public String getJobDetail(@P("职位ID") Long jobId) {
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            return "职位不存在或已下线。";
        }
        return "职位: " + job.getTitle() + "\n城市: " + job.getCity()
                + "\n薪资: " + job.getSalaryMin() + "-" + job.getSalaryMax() + "K"
                + "\n技能要求: " + job.getSkills()
                + "\n招聘人数: " + job.getHeadCount()
                + "\n职位描述: " + job.getJd();
    }

    @Tool("诊断当前用户简历的完善度，指出缺失项并给出修改建议。")
    public String diagnoseResume() {
        Long userId = UserContext.userId();
        if (userId == null) {
            return "用户未登录。";
        }
        return "用户ID=" + userId + "。请从「项目经历是否量化」「技能与目标岗位是否匹配」「是否有实习经历」三个维度给出改进建议。";
    }
}
