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
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", type);
        if (csrf) {
            var token = json.readTree(get(client, "/api/auth/csrf").body());
            builder.header(token.get("headerName").asText(), token.get("token").asText());
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
}
