package com.offermate.modules.resume.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.offermate.modules.resume.entity.ResumeFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResumeFileMapper extends BaseMapper<ResumeFile> {

    /** 按 (md5, 用户) 查一条记录，**忽略逻辑删除标记**：用于跨用户秒传后修正/复活自己的记录。 */
    @Select("select * from resume_file where md5 = #{md5} and user_id = #{userId} limit 1")
    ResumeFile selectIncludingDeleted(@Param("md5") String md5, @Param("userId") Long userId);

    /** 把一个失败/已删除的上传任务重置为「上传中」并换新的 uploadId（忽略逻辑删除标记）。 */
    @Update("update resume_file set deleted = 0, status = #{status}, upload_id = #{uploadId}, "
            + "update_time = now() where id = #{id}")
    int resetTask(@Param("id") Long id, @Param("status") String status, @Param("uploadId") String uploadId);

    /** 把一条（可能被逻辑删除的）记录修正为 DONE，并指向共享的存储对象。 */
    @Update("update resume_file set deleted = 0, status = #{status}, object_key = #{objectKey}, "
            + "size = #{size}, update_time = now() where id = #{id}")
    int revive(@Param("id") Long id, @Param("status") String status,
               @Param("objectKey") String objectKey, @Param("size") Long size);
}
