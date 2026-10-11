package com.example.creator.analytics;

import com.example.creator.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class CommentImportHttpTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;

    @Test
    void importsAndRetrievesOnlyOwnerSamplesWithCsrfAndRedaction() throws Exception {
        var a = client();
        var b = client();
        login(a, "creator-a@example.test");
        login(b, "creator-b@example.test");
        var account = send(a, "/api/accounts", "application/json", """
                {"name":"评论 HTTP 测试","audience":"程序员","positioning":"Java",
                 "columns":["Java 面试"],"weeklyTarget":3}
                """, true);
        assertThat(account.statusCode()).isEqualTo(201);
        var accountId = json.readTree(account.body()).get("id").asText();
        var path = "/api/metrics/review-evidence?accountId=" + accountId + "&q=Java";
        var empty = get(a, path);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body()).contains("NO_COMMENT_SAMPLES");
        var csv = "accountId,videoId,commentId,text,publishedAt,capturedAt,source\n"
                + accountId + ",http-video,c1,Java 联系13800138000或student@example.test,,2026-10-10T10:00:00Z,DEMO\n";
        var preview = upload(a, "preview", csv, true);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("[手机号]", "[邮箱]").doesNotContain("13800138000", "student@example.test");
        assertThat(upload(client(), "preview", csv, true).statusCode()).isEqualTo(401);
        assertThat(upload(a, "confirm", csv, false).statusCode()).isEqualTo(403);
        var foreign = upload(b, "preview", csv, true);
        assertThat(foreign.statusCode()).isEqualTo(200);
        assertThat(foreign.body()).contains("ACCOUNT_NOT_FOUND").doesNotContain("Java 联系");
        assertThat(upload(b, "confirm", csv, true).statusCode()).isEqualTo(422);
        assertThat(json.readTree(upload(a, "confirm", csv, true).body()).get("imported").asInt()).isEqualTo(1);
        assertThat(json.readTree(upload(a, "confirm", csv, true).body()).get("duplicates").asInt()).isEqualTo(1);
        var evidence = get(a, path);
        assertThat(evidence.statusCode()).isEqualTo(200);
        var sample = json.readTree(evidence.body()).get("comments").get(0);
        assertThat(sample.get("citationId").asText()).startsWith("comment:");
        assertThat(sample.get("source").asText()).isEqualTo("DEMO");
        assertThat(evidence.body()).doesNotContain("13800138000", "student@example.test");
        assertThat(get(b, path).statusCode()).isEqualTo(404);
        assertThat(get(client(), path).statusCode()).isEqualTo(401);
        assertThat(get(a, "/api/metrics/review-evidence?accountId=" + accountId + "&q=%20").statusCode()).isEqualTo(400);
        var conflict = csv.replace("Java 联系", "Java 讲解线程池 联系");
        assertThat(upload(a, "confirm", conflict, true).statusCode()).isEqualTo(422);
    }

    private HttpResponse<String> upload(HttpClient client, String action, String csv, boolean csrf) throws Exception {
        var boundary = "comments-test-boundary";
        var body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"comments.csv\""
                + "\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        return send(client, "/api/metrics/import/comments/" + action,
                "multipart/form-data; boundary=" + boundary, body, csrf);
    }
    private HttpResponse<String> send(HttpClient client, String path, String contentType, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType);
        if (csrf) {
            var token = json.readTree(get(client, "/api/auth/csrf").body());
            request.header(token.get("headerName").asText(), token.get("token").asText());
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private void login(HttpClient client, String email) throws Exception {
        assertThat(send(client, "/api/auth/login", "application/x-www-form-urlencoded",
                "email=" + email + "&password=development-fixture-only", true).statusCode()).isEqualTo(204);
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
