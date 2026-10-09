package com.example.creator.notification;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    NotificationService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public SettingsView settings(long ownerId) {
        return view(stored(ownerId));
    }

    @Transactional
    public SettingsView save(long ownerId, SettingsInput input) {
        if (input == null) throw new NotificationInvalid();
        var previous = stored(ownerId);
        var recipient = choose(input.emailRecipient(), previous.emailRecipient());
        var from = choose(input.smtpFromEmail(), previous.smtpFromEmail());
        var host = choose(input.smtpHost(), previous.smtpHost());
        var port = input.smtpPort() == null ? previous.smtpPort() : input.smtpPort();
        var username = encryptedOrOld(input.smtpUsername(), previous.smtpUsernameEnc());
        var password = encryptedOrOld(input.smtpPassword(), previous.smtpPasswordEnc());
        var webhook = encryptedOrOld(input.feishuWebhook(), previous.feishuWebhookEnc());
        var secret = encryptedOrOld(input.feishuSecret(), previous.feishuSecretEnc());
        if (input.feishuWebhook() != null && !input.feishuWebhook().isBlank()
                && !validFeishuWebhook(input.feishuWebhook()))
            throw new NotificationInvalid();
        if (input.emailEnabled() && (!email(recipient) || !email(from) || host == null || host.isBlank()
                || host.length() > 255 || port == null || port < 1 || port > 65535
                || username == null || password == null)
                || input.feishuEnabled() && (webhook == null || secret == null))
            throw new NotificationInvalid();
        jdbc.update("""
                INSERT INTO notification_settings (owner_id, email_recipient, smtp_from_email, email_enabled,
                    smtp_host, smtp_port, smtp_username_enc, smtp_password_enc,
                    feishu_enabled, feishu_webhook_enc, feishu_secret_enc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE email_recipient = ?, smtp_from_email = ?, email_enabled = ?, smtp_host = ?,
                    smtp_port = ?, smtp_username_enc = ?, smtp_password_enc = ?,
                    feishu_enabled = ?, feishu_webhook_enc = ?, feishu_secret_enc = ?
                """, ownerId, recipient, from, input.emailEnabled(), host, port, username, password,
                input.feishuEnabled(), webhook, secret, recipient, from, input.emailEnabled(), host, port,
                username, password, input.feishuEnabled(), webhook, secret);
        return settings(ownerId);
    }

    public void enqueue(long ownerId, String triggerId, String runId) {
        var settings = stored(ownerId);
        if (settings.emailEnabled()) insert(ownerId, triggerId, runId, "EMAIL");
        if (settings.feishuEnabled()) insert(ownerId, triggerId, runId, "FEISHU");
    }

    Optional<EmailDelivery> pendingEmail(long ownerId, String triggerId) {
        var settings = stored(ownerId);
        if (!settings.emailEnabled()) return Optional.empty();
        return jdbc.query("""
                SELECT id, generation_run_id FROM notification_deliveries
                WHERE owner_id = ? AND trigger_id = ? AND channel = 'EMAIL' AND status = 'PENDING'
                """, (row, ignored) -> new EmailDelivery(row.getString("id"),
                row.getString("generation_run_id"), settings.emailRecipient(),
                settings.smtpFromEmail(), settings.smtpHost(), settings.smtpPort(),
                settings.smtpUsernameEnc(), settings.smtpPasswordEnc()), ownerId, triggerId)
                .stream().findFirst();
    }

    void finishEmail(long ownerId, String deliveryId, String status, String code) {
        jdbc.update("""
                UPDATE notification_deliveries SET status = ?, error_code = ?
                WHERE id = ? AND owner_id = ? AND channel = 'EMAIL' AND status = 'PENDING'
                """, status, code, deliveryId, ownerId);
    }

    Optional<FeishuDelivery> pendingFeishu(long ownerId, String triggerId) {
        var settings = stored(ownerId);
        if (!settings.feishuEnabled()) return Optional.empty();
        return jdbc.query("""
                SELECT id, generation_run_id FROM notification_deliveries
                WHERE owner_id = ? AND trigger_id = ? AND channel = 'FEISHU' AND status = 'PENDING'
                """, (row, ignored) -> new FeishuDelivery(row.getString("id"),
                row.getString("generation_run_id"), settings.feishuWebhookEnc(),
                settings.feishuSecretEnc()), ownerId, triggerId).stream().findFirst();
    }

    void finishFeishu(long ownerId, String deliveryId, String status, String code) {
        jdbc.update("""
                UPDATE notification_deliveries SET status = ?, error_code = ?
                WHERE id = ? AND owner_id = ? AND channel = 'FEISHU' AND status = 'PENDING'
                """, status, code, deliveryId, ownerId);
    }

    private void insert(long ownerId, String triggerId, String runId, String channel) {
        jdbc.update("""
                INSERT INTO notification_deliveries (id, owner_id, trigger_id, generation_run_id,
                    channel, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                ON DUPLICATE KEY UPDATE id = id
                """, UUID.randomUUID().toString(), ownerId, triggerId, runId, channel);
    }

    private Stored stored(long ownerId) {
        List<Stored> rows = jdbc.query("""
                SELECT email_recipient, smtp_from_email, email_enabled, smtp_host, smtp_port,
                    smtp_username_enc, smtp_password_enc, feishu_enabled,
                    feishu_webhook_enc, feishu_secret_enc
                FROM notification_settings WHERE owner_id = ?
                """, (row, ignored) -> new Stored(row.getString("email_recipient"),
                row.getString("smtp_from_email"), row.getBoolean("email_enabled"), row.getString("smtp_host"),
                (Integer) row.getObject("smtp_port"), row.getString("smtp_username_enc"),
                row.getString("smtp_password_enc"), row.getBoolean("feishu_enabled"),
                row.getString("feishu_webhook_enc"), row.getString("feishu_secret_enc")), ownerId);
        return rows.isEmpty() ? new Stored(null, null, false, null, null, null, null,
                false, null, null) : rows.getFirst();
    }

    private static SettingsView view(Stored stored) {
        return new SettingsView(stored.emailRecipient(), stored.smtpFromEmail(), stored.emailEnabled(), stored.smtpHost(),
                stored.smtpPort(), stored.smtpUsernameEnc() != null, stored.smtpPasswordEnc() != null,
                stored.feishuEnabled(), stored.feishuWebhookEnc() != null,
                stored.feishuSecretEnc() != null);
    }

    private String encryptedOrOld(String incoming, String previous) {
        return incoming == null || incoming.isBlank() ? previous : cipher.encrypt(incoming);
    }

    private static String choose(String incoming, String previous) {
        return incoming == null ? previous : incoming.isBlank() ? null : incoming.strip();
    }

    private static boolean email(String value) {
        return value != null && value.length() <= 254
                && value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    static boolean validFeishuWebhook(String value) {
        try {
            var uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "open.feishu.cn".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]+");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public record SettingsInput(String emailRecipient, String smtpFromEmail, boolean emailEnabled, String smtpHost,
                                Integer smtpPort, String smtpUsername, String smtpPassword,
                                boolean feishuEnabled, String feishuWebhook, String feishuSecret) { }
    public record SettingsView(String emailRecipient, String smtpFromEmail, boolean emailEnabled, String smtpHost,
                               Integer smtpPort, boolean smtpUsernameConfigured,
                               boolean smtpPasswordConfigured, boolean feishuEnabled,
                               boolean feishuWebhookConfigured, boolean feishuSecretConfigured) { }
    record EmailDelivery(String id, String runId, String recipient, String from,
                         String host, int port, String usernameEnc, String passwordEnc) { }
    record FeishuDelivery(String id, String runId, String webhookEnc, String secretEnc) { }
    private record Stored(String emailRecipient, String smtpFromEmail, boolean emailEnabled, String smtpHost,
                          Integer smtpPort, String smtpUsernameEnc, String smtpPasswordEnc,
                          boolean feishuEnabled, String feishuWebhookEnc, String feishuSecretEnc) { }
    static final class NotificationInvalid extends RuntimeException {
        NotificationInvalid() { super("INVALID_NOTIFICATION_SETTINGS", null, false, false); }
    }
}
