package com.offermate.modules.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历文件（内容级去重 / 秒传 / 分片续传 的核心载体）。
 * md5 上有唯一索引：这是"内容级去重"的最终兜底，Redis 只是前置拦截。
 */
@Data
@TableName("resume_file")
public class ResumeFile {

    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_MERGING = "MERGING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 文件内容指纹，秒传与去重的依据 */
    private String md5;

    private String fileName;

    private String objectKey;

    private Long size;

    /** MinIO 分片上传 uploadId，断点续传靠它找回上下文 */
    private String uploadId;

    private String status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
