package com.example.creator.notification;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import com.example.creator.content.ContentService;
import com.example.creator.content.GenerationJobsService;
import com.example.creator.content.GenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DEV_USER_PASSWORD=development-fixture-only",
        "NOTIFICATION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@ActiveProfiles("dev")
class NotificationSettingsTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationService notifications;
    @Autowired private AccountService accounts;
    @Autowired private ContentService content;
    @Autowired private GenerationJobsService jobs;
    @Autowired private PasswordEncoder passwords;
    @MockitoBean private GenerationService generation;
    @MockitoBean private EmailSender emails;
    @MockitoBean private FeishuSender feishu;

    @Test
    void settingsAreOwnerScopedAndResponsesNeverExposeCredentials() throws Exception {
        var a = client();
        var b = client();
        assertThat(get(a, "/api/notification-settings").statusCode()).isEqualTo(401);
        login(a, "creator-a@example.test");
        login(b, "creator-b@example.test");
        var payload = """
                {"emailRecipient":"owner@example.test","smtpFromEmail":"sender@example.test","emailEnabled":true,
                 "smtpHost":"smtp.example.test","smtpPort":587,
                 "smtpUsername":"smtp-user","smtpPassword":"smtp-secret",
                 "feishuEnabled":true,
                 "feishuWebhook":"https://open.feishu.cn/open-apis/bot/v2/hook/test-token",
                 "feishuSecret":"sign-secret"}
                """;
        var saved = put(a, payload);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.body()).contains("owner@example.test", "smtpPasswordConfigured\":true",
                "feishuWebhookConfigured\":true").doesNotContain("smtp-user", "smtp-secret",
                "test-token", "sign-secret", "NOTIFICATION_ENCRYPTION_KEY");
        assertThat(get(a, "/api/notification-settings").body()).isEqualTo(saved.body());
        assertThat(get(b, "/api/notification-settings").body()).doesNotContain("owner@example.test");
        assertThat(get(a, "/api/notification-settings/deliveries").body()).isEqualTo("[]");
        var ownerId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        var ciphertext = jdbc.queryForObject("SELECT smtp_password_enc FROM notification_settings "
                + "WHERE owner_id = ?", String.class, ownerId);
        assertThat(ciphertext).startsWith("v1:").doesNotContain("smtp-secret");
        assertThat(put(a, "{\"emailEnabled\":true,\"feishuEnabled\":true}").body())
                .contains("smtpPasswordConfigured\":true", "feishuSecretConfigured\":true");
        assertThat(put(a, "{\"emailEnabled\":false,\"feishuEnabled\":true,"
                + "\"feishuWebhook\":\"https://open.feishu.cn.evil.test/open-apis/bot/v2/hook/x\"}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void successfulJobQueuesConfiguredChannelsButFailedJobDoesNot() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)",
                email, passwords.encode("development-fixture-only"), "通知验收");
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        notifications.save(owner, new NotificationService.SettingsInput("owner@example.test", "sender@example.test", true,
                "smtp.example.test", 587, "smtp-user", "smtp-secret", true,
                "https://open.feishu.cn/open-apis/bot/v2/hook/test-token", "sign-secret"));
        var account = accounts.create(owner, new AccountService.AccountInput("通知测试", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), ""),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), ""),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), ""));
        var job = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(1,
                LocalTime.of(9, 0), "Asia/Shanghai", false, "测试通知", slots));
        var started = content.startRun(owner, account.id(), "WEEK_PLAN");
        var completed = content.finishRun(owner, started.id(), "week-batch-1", 6);
        when(generation.generate(eq(owner), any())).thenReturn(completed)
                .thenThrow(new IllegalStateException("test failure"));
        doThrow(new IllegalStateException("email unavailable"))
                .when(emails).deliverPending(eq(owner), any());
        var trigger = jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.MANUAL);
        verify(feishu).deliverPending(owner, trigger.id());
        assertThat(jdbc.queryForList("SELECT channel FROM notification_deliveries "
                + "WHERE owner_id = ? AND trigger_id = ? ORDER BY channel", String.class, owner,
                trigger.id())).containsExactly("EMAIL", "FEISHU");
        assertThat(notifications.deliveries(owner)).hasSize(2);
        assertThat(notifications.deliveries(owner + 999_999)).isEmpty();
        var ownerClient = client();
        var otherClient = client();
        login(ownerClient, email);
        login(otherClient, "creator-b@example.test");
        var ownedDeliveries = get(ownerClient, "/api/notification-settings/deliveries");
        assertThat(ownedDeliveries.statusCode()).isEqualTo(200);
        assertThat(ownedDeliveries.body()).contains(completed.id(), "PENDING")
                .doesNotContain("smtp-secret", "smtp-user", "test-token", "sign-secret");
        assertThat(get(otherClient, "/api/notification-settings/deliveries").body())
                .doesNotContain(completed.id());
        assertThat(get(ownerClient, "/api/generations/" + completed.id()).statusCode()).isEqualTo(200);
        assertThat(get(otherClient, "/api/generations/" + completed.id()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notification_deliveries "
                + "WHERE owner_id = ?", String.class, owner)).containsExactly("PENDING");
        assertThatThrownBy(() -> jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.MANUAL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE owner_id = ?",
                Integer.class, owner)).isEqualTo(2);
        notifications.enqueue(owner, trigger.id(), trigger.generationRunId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE owner_id = ?",
                Integer.class, owner)).isEqualTo(2);
        var first = notifications.claimFeishu(owner, trigger.id()).orElseThrow();
        assertThat(notifications.claimFeishu(owner, trigger.id())).isEmpty();
        for (int attempt = 2; attempt <= 3; attempt++) {
            notifications.finishFeishu(owner, first.id(), "RETRY", "FEISHU_RATE_LIMITED");
            assertThat(notifications.dueDeliveries()).noneMatch(due -> due.triggerId().equals(trigger.id())
                    && due.channel().equals("FEISHU"));
            jdbc.update("UPDATE notification_deliveries SET next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(6),"
                    + " INTERVAL 1 SECOND) WHERE id = ?", first.id());
            assertThat(notifications.dueDeliveries()).anyMatch(due -> due.triggerId().equals(trigger.id())
                    && due.channel().equals("FEISHU"));
            assertThat(notifications.claimFeishu(owner, trigger.id())).isPresent();
        }
        notifications.finishFeishu(owner, first.id(), "RETRY", "FEISHU_RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE id = ?",
                String.class, first.id())).isEqualTo("FAILED");
        assertThat(notifications.claimFeishu(owner, trigger.id())).isEmpty();
        var emailDelivery = notifications.claimEmail(owner, trigger.id()).orElseThrow();
        jdbc.update("UPDATE notification_deliveries SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(6),"
                + " INTERVAL 3 MINUTE) WHERE id = ?", emailDelivery.id());
        notifications.markAbandonedUnknown();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE id = ?",
                String.class, emailDelivery.id())).isEqualTo("UNKNOWN");
    }

    @Test
    void notificationsQueuedBeforeAnInterruptionRemainDispatchable() {
        var email = "pending-notification-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)",
                email, passwords.encode("development-fixture-only"), "pending notification");
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        notifications.save(owner, new NotificationService.SettingsInput("owner@example.test", "sender@example.test", true,
                "smtp.example.test", 587, "smtp-user", "smtp-secret", true,
                "https://open.feishu.cn/open-apis/bot/v2/hook/test-token", "sign-secret"));
        var account = accounts.create(owner, new AccountService.AccountInput("待发送恢复", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), ""),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), ""),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), ""));
        var job = jobs.save(owner, account.id(), new GenerationJobsService.JobInput(1,
                LocalTime.of(9, 0), "Asia/Shanghai", false, "恢复演练", slots));
        var run = content.startRun(owner, account.id(), "WEEK_PLAN");
        when(generation.generate(eq(owner), any())).thenReturn(content.finishRun(owner, run.id(), "week-fixture", 6));
        var trigger = jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.MANUAL);

        // Senders are mocks, leaving the committed queue as if the process stopped before first delivery.
        assertThat(notifications.dueDeliveries().stream().filter(due -> due.triggerId().equals(trigger.id()))
                .map(NotificationService.DueDelivery::channel)).containsExactlyInAnyOrder("EMAIL", "FEISHU");
        notifications.save(owner, new NotificationService.SettingsInput(null, null, false,
                null, null, null, null, false, null, null));
        assertThat(notifications.dueDeliveries()).noneMatch(due -> due.triggerId().equals(trigger.id()));
        notifications.save(owner, new NotificationService.SettingsInput(null, null, true,
                null, null, null, null, true, null, null));
        var recoveredEmails = org.mockito.Mockito.mock(EmailSender.class);
        var recoveredFeishu = org.mockito.Mockito.mock(FeishuSender.class);
        new NotificationRetryScanner(notifications, recoveredEmails, recoveredFeishu).scan();
        verify(recoveredEmails).deliverPending(owner, trigger.id());
        verify(recoveredFeishu).deliverPending(owner, trigger.id());
        var emailDelivery = notifications.claimEmail(owner, trigger.id()).orElseThrow();
        notifications.finishEmail(owner, emailDelivery.id(), "SENT", null);
        var feishuDelivery = notifications.claimFeishu(owner, trigger.id()).orElseThrow();
        notifications.finishFeishu(owner, feishuDelivery.id(), "UNKNOWN", "FEISHU_SEND_UNKNOWN");
        assertThat(notifications.dueDeliveries()).noneMatch(due -> due.triggerId().equals(trigger.id()));
        assertThat(notifications.claimEmail(owner, trigger.id())).isEmpty();
        assertThat(notifications.claimFeishu(owner, trigger.id())).isEmpty();
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private void login(HttpClient client, String email) throws Exception {
        var form = "email=" + email + "&password=development-fixture-only";
        assertThat(send(client, "/api/auth/login", "POST", "application/x-www-form-urlencoded", form)
                .statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> put(HttpClient client, String body) throws Exception {
        return send(client, "/api/notification-settings", "PUT", "application/json", body);
    }

    private HttpResponse<String> send(HttpClient client, String path, String method,
                                      String contentType, String body) throws Exception {
        var csrf = json.readTree(get(client, "/api/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType)
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
