package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class OperationReviewHttpTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private ReviewJobScanner scanner;

    @Test
    void weeklyJobSettingsAndRecordsAreCsrfProtectedAndOwnerScopedWithTheServerPaused() throws Exception {
        var a = client(); var b = client();
        login(a, "creator-a@example.test"); login(b, "creator-b@example.test");
        var created = post(a, "/api/accounts", "application/json", """
                {"name":"DEMO 定时 HTTP","audience":"程序员","positioning":"Java","columns":["Java 面试"],"weeklyTarget":2}
                """, true);
        var accountId = json.readTree(created.body()).get("id").asText();
        var path = "/api/metrics/review-jobs/accounts/" + accountId;
        var empty = json.readTree(get(a, path).body());
        assertThat(empty.get("job").isNull()).isTrue();
        assertThat(empty.get("schedulerEnabled").asBoolean()).isFalse();
        var input = """
                {"dayOfWeek":7,"localTime":"09:00","timeZone":"Asia/Shanghai","enabled":true,"topic":"线程池"}
                """;
        assertThat(get(b, path).statusCode()).isEqualTo(404);
        assertThat(get(client(), path).statusCode()).isEqualTo(401);
        assertThat(send(a, path, "application/json", input, false, "PUT").statusCode()).isEqualTo(403);
        assertThat(send(b, path, "application/json", input, true, "PUT").statusCode()).isEqualTo(404);
        assertThat(send(a, path, "application/json", input.replace("Asia/Shanghai", "Bad/Zone"), true, "PUT").statusCode()).isEqualTo(400);
        var saved = json.readTree(send(a, path, "application/json", input, true, "PUT").body());
        var jobId = saved.get("job").get("id").asText();
        assertThat(saved.get("triggers")).isEmpty();
        scanner.scheduledScan();
        assertThat(json.readTree(get(a, path).body()).get("triggers")).isEmpty();
        scanner.scanDue(java.time.Instant.parse("2026-10-11T01:05:00Z"));
        var trigger = json.readTree(get(a, path).body()).get("triggers").get(0);
        assertThat(trigger.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(get(a, "/api/metrics/reviews/" + trigger.get("reportId").asText()).statusCode()).isEqualTo(200);
        assertThat(get(b, "/api/metrics/reviews/" + trigger.get("reportId").asText()).statusCode()).isEqualTo(404);
        var disabled = json.readTree(send(a, path, "application/json", input.replace("true", "false"), true, "PUT").body());
        assertThat(disabled.get("job").get("id").asText()).isEqualTo(jobId);
        assertThat(disabled.get("triggers")).hasSize(1);
    }

    @Test
    void savesAndReplaysAnOwnedReportWithCsrfAndHidesItFromOtherUsers() throws Exception {
        var a = client();
        var b = client();
        login(a, "creator-a@example.test");
        login(b, "creator-b@example.test");
        var account = post(a, "/api/accounts", "application/json", """
                {"name":"复盘 HTTP 测试","audience":"程序员","positioning":"Java","columns":["Java 面试"],"weeklyTarget":2}
                """, true);
        var accountId = json.readTree(account.body()).get("id").asText();
        var input = "{\"accountId\":\"" + accountId + "\",\"topic\":\"Java\",\"from\":\"2026-10-10T00:00:00Z\","
                + "\"to\":\"2026-10-11T00:00:00Z\",\"requestId\":\"" + UUID.randomUUID() + "\"}";
        var created = post(a, "/api/metrics/reviews", "application/json", input, true);
        assertThat(created.statusCode()).isEqualTo(201);
        var report = json.readTree(created.body());
        assertThat(report.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        var id = report.get("id").asText();
        assertThat(get(a, "/api/metrics/reviews/" + id).statusCode()).isEqualTo(200);
        assertThat(get(b, "/api/metrics/reviews/" + id).statusCode()).isEqualTo(404);
        assertThat(get(b, "/api/metrics/reviews?accountId=" + accountId).statusCode()).isEqualTo(404);
        assertThat(get(client(), "/api/metrics/reviews/" + id).statusCode()).isEqualTo(401);
        assertThat(post(a, "/api/metrics/reviews", "application/json", input, false).statusCode()).isEqualTo(403);
        assertThat(post(b, "/api/metrics/reviews", "application/json", input, true).statusCode()).isEqualTo(404);
        assertThat(json.readTree(post(a, "/api/metrics/reviews", "application/json", input, true).body()).get("id").asText()).isEqualTo(id);
        assertThat(post(a, "/api/metrics/reviews", "application/json", input.replace("\"Java\"", "\"Redis\""), true).statusCode()).isEqualTo(409);
        assertThat(post(a, "/api/metrics/reviews", "application/json", input.replace("2026-10-11", "2026-10-09"), true).statusCode()).isEqualTo(400);
        assertThat(json.readTree(get(a, "/api/metrics/reviews?accountId=" + accountId).body())).hasSize(1);
    }
    private void login(HttpClient client, String email) throws Exception {
        assertThat(post(client, "/api/auth/login", "application/x-www-form-urlencoded",
                "email=" + email + "&password=development-fixture-only", true).statusCode()).isEqualTo(204);
    }
    private HttpResponse<String> post(HttpClient client, String path, String type, String body, boolean csrf) throws Exception {
        return send(client, path, type, body, csrf, "POST");
    }
    private HttpResponse<String> send(HttpClient client, String path, String type, String body, boolean csrf, String method) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", type);
        if (csrf) {
            var token = json.readTree(get(client, "/api/auth/csrf").body());
            builder.header(token.get("headerName").asText(), token.get("token").asText());
        }
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
}
