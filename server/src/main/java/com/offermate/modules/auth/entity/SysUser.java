package com.offermate.modules.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    public static final String ROLE_SEEKER = "SEEKER";
    public static final String ROLE_HR = "HR";
    public static final String ROLE_ADMIN = "ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    private String password;

    private String nickname;

    private String role;

    private Long companyId;

    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
