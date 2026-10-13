package com.example.creator.notification;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRetryScannerTest {
    @Test
    void failedEmailRetryDoesNotStopFeishuRetry() {
        var notifications = mock(NotificationService.class);
        var emails = mock(EmailSender.class);
        var feishu = mock(FeishuSender.class);
        when(notifications.dueRetries()).thenReturn(List.of(
                new NotificationService.DueDelivery(7, "email-trigger", "EMAIL"),
                new NotificationService.DueDelivery(8, "feishu-trigger", "FEISHU")));
        doThrow(new IllegalStateException("mail unavailable"))
                .when(emails).deliverPending(7, "email-trigger");
        new NotificationRetryScanner(notifications, emails, feishu).scan();
        verify(notifications).markAbandonedUnknown();
        verify(feishu).deliverPending(8, "feishu-trigger");
    }
}
