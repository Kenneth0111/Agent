package com.example.creator.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationRetryScanner {
    private static final Logger log = LoggerFactory.getLogger(NotificationRetryScanner.class);
    private final NotificationService notifications;
    private final EmailSender emails;
    private final FeishuSender feishu;

    NotificationRetryScanner(NotificationService notifications, EmailSender emails, FeishuSender feishu) {
        this.notifications = notifications;
        this.emails = emails;
        this.feishu = feishu;
    }

    @Scheduled(fixedDelay = 60_000)
    public void scan() {
        notifications.markAbandonedUnknown();
        for (var due : notifications.dueRetries()) {
            try {
                if ("EMAIL".equals(due.channel())) emails.deliverPending(due.ownerId(), due.triggerId());
                if ("FEISHU".equals(due.channel())) feishu.deliverPending(due.ownerId(), due.triggerId());
            } catch (RuntimeException failed) {
                log.warn("notification retry failed: triggerId={} channel={} type={}",
                        due.triggerId(), due.channel(), failed.getClass().getSimpleName());
            }
        }
    }
}
