package com.example.creator.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class EmailSender {
    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    private final NotificationService notifications;
    private final SecretCipher cipher;
    private final String publicUrl;

    EmailSender(NotificationService notifications, SecretCipher cipher,
                @Value("${creator.notification.public-url}") String publicUrl) {
        this.notifications = notifications;
        this.cipher = cipher;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
    }

    public void deliverPending(long ownerId, String triggerId) {
        var pending = notifications.claimEmail(ownerId, triggerId);
        if (pending.isEmpty()) return;
        var delivery = pending.get();
        try {
            var sender = new JavaMailSenderImpl();
            sender.setHost(delivery.host());
            sender.setPort(delivery.port());
            sender.setUsername(cipher.decrypt(delivery.usernameEnc()));
            sender.setPassword(cipher.decrypt(delivery.passwordEnc()));
            sender.setDefaultEncoding("UTF-8");
            var properties = sender.getJavaMailProperties();
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.connectiontimeout", "5000");
            properties.put("mail.smtp.timeout", "10000");
            properties.put("mail.smtp.writetimeout", "10000");
            var message = new SimpleMailMessage();
            message.setFrom(delivery.from());
            message.setTo(delivery.recipient());
            message.setSubject("创作工作台：本周草稿已生成");
            message.setText("本周草稿已生成。请登录工作台查看并审阅：\n"
                    + publicUrl + "?run=" + delivery.runId());
            sender.send(message);
            notifications.finishEmail(ownerId, delivery.id(), "SENT", null);
        } catch (MailAuthenticationException | MailParseException rejected) {
            failed(ownerId, delivery.id(), "SMTP_REJECTED", "FAILED");
        } catch (MailException uncertain) {
            failed(ownerId, delivery.id(), "SMTP_SEND_UNKNOWN", "UNKNOWN");
        } catch (SecretCipher.KeyUnavailable missing) {
            failed(ownerId, delivery.id(), "NOTIFICATION_KEY_UNAVAILABLE", "FAILED");
        } catch (IllegalStateException invalid) {
            failed(ownerId, delivery.id(), "NOTIFICATION_SECRET_INVALID", "FAILED");
        }
    }

    private void failed(long ownerId, String id, String code, String status) {
        notifications.finishEmail(ownerId, id, status, code);
        log.warn("email delivery {} finished: status={} code={}", id, status, code);
    }
}
