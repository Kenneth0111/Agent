package com.example.creator.notification;

import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailSenderTest {
    private final SecretCipher cipher = new SecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final NotificationService notifications = mock(NotificationService.class);
    private final EmailSender emails = new EmailSender(notifications, cipher,
            "https://creator.example.test/");

    @Test
    void sendsOnlySummaryAndResultLinkThenRecordsSuccess() {
        when(notifications.claimEmail(7, "trigger-1")).thenReturn(Optional.of(delivery()));
        try (var senders = Mockito.mockConstruction(JavaMailSenderImpl.class, (sender, context) ->
                when(sender.getJavaMailProperties()).thenReturn(new Properties()))) {
            emails.deliverPending(7, "trigger-1");
            var sender = senders.constructed().getFirst();
            var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getFrom()).isEqualTo("sender@example.test");
            assertThat(message.getValue().getTo()).containsExactly("owner@example.test");
            assertThat(message.getValue().getText()).contains("https://creator.example.test/?run=run-1")
                    .doesNotContain("smtp-secret", "smtp-user");
            verify(notifications).finishEmail(7, "delivery-1", "SENT", null);
        }
    }

    @Test
    void authenticationRejectionRecordsFailureWithoutLeakingProviderDetails() {
        when(notifications.claimEmail(7, "trigger-1")).thenReturn(Optional.of(delivery()));
        try (var senders = Mockito.mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.getJavaMailProperties()).thenReturn(new Properties());
            doThrow(new MailAuthenticationException("provider-secret-text"))
                    .when(sender).send(any(SimpleMailMessage.class));
        })) {
            emails.deliverPending(7, "trigger-1");
            verify(notifications).finishEmail(7, "delivery-1", "FAILED", "SMTP_REJECTED");
        }
    }

    private NotificationService.EmailDelivery delivery() {
        return new NotificationService.EmailDelivery("delivery-1", "run-1",
                "owner@example.test", "sender@example.test", "smtp.example.test", 587,
                cipher.encrypt("smtp-user"), cipher.encrypt("smtp-secret"));
    }
}
