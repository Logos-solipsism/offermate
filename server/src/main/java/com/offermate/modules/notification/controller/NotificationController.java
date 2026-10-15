package com.offermate.modules.notification.controller;

import com.offermate.common.result.R;
import com.offermate.modules.notification.entity.Notification;
import com.offermate.modules.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "站内信")
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    @Operation(summary = "我的站内信（投递成功 / 解析完成 / 状态变更都由 MQ 消费者异步写入）")
    @GetMapping("/mine")
    public R<List<Notification>> mine() {
        return R.ok(notificationQueryService.myNotifications());
    }
}
