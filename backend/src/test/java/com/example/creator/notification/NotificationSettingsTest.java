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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @MockitoBean private GenerationService generation;
    @MockitoBean private EmailSender emails;

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
        var ownerId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        var ciphertext = jdbc.queryForObject("SELECT smtp_password_enc FROM notification_settings "
                + "WHERE owner_id = ?", String.class, ownerId);
        assertThat(ciphertext).startsWith("v1:").doesNotContain("smtp-secret");
        assertThat(put(a, "{\"emailEnabled\":true,\"feishuEnabled\":true}").body())
                .contains("smtpPasswordConfigured\":true", "feishuSecretConfigured\":true");
    }

    @Test
    void successfulJobQueuesConfiguredChannelsButFailedJobDoesNot() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)",
                email, "test-only", "通知验收");
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
        var trigger = jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.MANUAL);
        assertThat(jdbc.queryForList("SELECT channel FROM notification_deliveries "
                + "WHERE owner_id = ? AND trigger_id = ? ORDER BY channel", String.class, owner,
                trigger.id())).containsExactly("EMAIL", "FEISHU");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notification_deliveries "
                + "WHERE owner_id = ?", String.class, owner)).containsExactly("PENDING");
        assertThatThrownBy(() -> jobs.trigger(owner, job.id(), GenerationJobsService.TriggerSource.MANUAL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE owner_id = ?",
                Integer.class, owner)).isEqualTo(2);
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
