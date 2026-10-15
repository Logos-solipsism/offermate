package com.offermate.modules.notification.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.offermate.common.context.UserContext;
import com.offermate.common.exception.BizException;
import com.offermate.modules.notification.entity.Notification;
import com.offermate.modules.notification.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 站内信查询：只能看自己的（收件人维度在查询条件里，不靠前端过滤）。 */
@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private static final int MAX_ROWS = 50;

    private final NotificationMapper notificationMapper;

    public List<Notification> myNotifications() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .last("limit " + MAX_ROWS));
    }
}
