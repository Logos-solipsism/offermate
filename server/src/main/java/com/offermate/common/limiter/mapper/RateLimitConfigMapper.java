package com.offermate.common.limiter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offermate.common.limiter.RateLimitConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RateLimitConfigMapper extends BaseMapper<RateLimitConfig> {
}
