package com.offermate.modules.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("application")
public class Application {

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_VIEWED = "VIEWED";
    public static final String STATUS_INTERVIEW = "INTERVIEW";
    public static final String STATUS_OFFER = "OFFER";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long jobId;

    private Long resumeId;

    /** 投递时的简历内容指纹，用于内容级去重 */
    private String resumeMd5;

    private String status;

    private String hrFeedback;

    /** 异步计算的 JD 匹配分 */
    private Integer matchScore;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
