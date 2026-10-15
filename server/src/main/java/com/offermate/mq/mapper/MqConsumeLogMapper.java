package com.offermate.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offermate.mq.entity.MqConsumeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MqConsumeLogMapper extends BaseMapper<MqConsumeLog> {

    @Update("update mq_consume_log set status = #{status}, error_msg = #{errorMsg}, "
            + "retry_count = retry_count + 1, update_time = now() where msg_id = #{msgId}")
    int updateStatus(@Param("msgId") String msgId,
                     @Param("status") String status,
                     @Param("errorMsg") String errorMsg);
}
