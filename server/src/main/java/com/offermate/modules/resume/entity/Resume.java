package com.offermate.modules.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume")
public class Resume {

    public static final String PARSE_PENDING = "PENDING";
    public static final String PARSE_PARSING = "PARSING";
    public static final String PARSE_DONE = "DONE";
    public static final String PARSE_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long fileId;

    private String title;

    /** 异步解析出的结构化 JSON */
    private String parsedJson;

    private String parseStatus;

    private Integer parseRetry;

    private Integer isDefault;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
