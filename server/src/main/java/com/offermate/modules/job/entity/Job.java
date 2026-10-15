package com.offermate.modules.job.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hrId;

    private String title;

    private String city;

    private Integer salaryMin;

    private Integer salaryMax;

    private String skills;

    private String jd;

    private Integer headCount;

    private Integer status;

    private Integer auditStatus;

    private Integer viewCount;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
