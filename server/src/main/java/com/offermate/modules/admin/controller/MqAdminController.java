package com.offermate.modules.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.result.R;
import com.offermate.modules.resume.entity.ResumeFile;
import com.offermate.modules.resume.mapper.ResumeFileMapper;
import com.offermate.mq.MqEvents;
import com.offermate.mq.MqProducer;
import com.offermate.mq.MqTopics;
import com.offermate.mq.entity.MqConsumeLog;
import com.offermate.mq.mapper.MqConsumeLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 运维后台：MQ 消费/死信看板 + 死信重放。
 * 重放不依赖消息体，而是按业务键（fileId）从业务表重建事件，
 * 这样即使消息体结构升级过，历史死信也能重放。
 */
@RestController
@RequestMapping("/api/admin/mq")
@RequiredArgsConstructor
public class MqAdminController {

    private final MqConsumeLogMapper consumeLogMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final MqProducer mqProducer;

    @GetMapping("/logs")
    public R<List<MqConsumeLog>> logs(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "50") int limit) {
        return R.ok(consumeLogMapper.selectList(Wrappers.<MqConsumeLog>lambdaQuery()
                .eq(status != null && !status.isBlank(), MqConsumeLog::getStatus, status)
                .orderByDesc(MqConsumeLog::getUpdateTime)
                .last("limit " + Math.min(limit, 200))));
    }

    @PostMapping("/replay/resume/{fileId}")
    public R<String> replayResumeParse(@PathVariable Long fileId) {
        ResumeFile file = resumeFileMapper.selectById(fileId);
        if (file == null) {
            return R.fail(404, "文件不存在");
        }
        mqProducer.sendAsync(MqTopics.RESUME_PARSE, "resume:" + fileId,
                new MqEvents.ResumeParseEvent(file.getId(), file.getUserId(), file.getObjectKey(),
                        file.getMd5(), file.getFileName(), 0));
        return R.ok("已重新投递解析任务: fileId=" + fileId);
    }
}
