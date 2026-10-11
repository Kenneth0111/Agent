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
class MetricsImportHttpTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;

    @Test
    void authenticatedUploadIsOwnerScopedCsrfProtectedAndIdempotent() throws Exception {
        var a = client();
        var b = client();
        login(a, "creator-a@example.test");
        login(b, "creator-b@example.test");
        var account = send(a, "/api/accounts", "application/json", """
                {"name":"指标导入测试","audience":"程序员","positioning":"Java",
                 "columns":["Java 面试"],"weeklyTarget":3}
                """, true);
        assertThat(account.statusCode()).isEqualTo(201);
        var accountId = json.readTree(account.body()).get("id").asText();
        var csv = "accountId,videoId,capturedAt,views,likes,commentCount,source\n"
                + accountId + ",http-video,2026-10-10T10:00:00Z,100,0,,DEMO_MANUAL\n";
        var preview = upload(a, "preview", csv, true);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(json.readTree(preview.body()).get("canImport").asBoolean()).isTrue();
        var largePreview = json.readTree(upload(a, "preview", csv.replace(",100,0,,", ",9223372036854775807,0,,"), true).body());
        assertThat(largePreview.get("rows").get(0).get("data").get("views").isTextual()).isTrue();
        assertThat(largePreview.get("rows").get(0).get("data").get("views").asText()).isEqualTo("9223372036854775807");
        assertThat(largePreview.get("rows").get(0).get("data").get("commentCount").isNull()).isTrue();
        assertThat(upload(client(), "preview", csv, true).statusCode()).isEqualTo(401);
        assertThat(upload(a, "confirm", csv, false).statusCode()).isEqualTo(403);
        var foreign = upload(b, "preview", csv, true);
        assertThat(foreign.statusCode()).isEqualTo(200);
        assertThat(foreign.body()).contains("ACCOUNT_NOT_FOUND");
        assertThat(upload(b, "confirm", csv, true).statusCode()).isEqualTo(422);
        var confirmed = upload(a, "confirm", csv, true);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(confirmed.body()).get("imported").asInt()).isEqualTo(1);
        var repeated = json.readTree(upload(a, "confirm", csv, true).body());
        assertThat(repeated.get("imported").asInt()).isZero();
        assertThat(repeated.get("duplicates").asInt()).isEqualTo(1);
        var conflict = upload(a, "confirm", csv.replace(",100,0,,", ",101,0,,"), true);
        assertThat(conflict.statusCode()).isEqualTo(422);
        assertThat(conflict.body()).contains("SNAPSHOT_CONFLICT");
        var works = get(a, "/api/metrics/works?accountId=" + accountId);
        assertThat(works.statusCode()).isEqualTo(200);
        var workId = json.readTree(works.body()).get(0).get("id").asText();
        var path = "/api/metrics/works/" + workId + "/trend?source=DEMO_MANUAL";
        var trend = get(a, path);
        assertThat(trend.statusCode()).isEqualTo(200);
        var point = json.readTree(trend.body()).get("points").get(0);
        assertThat(point.get("views").isTextual()).isTrue();
        assertThat(point.get("views").asText()).isEqualTo("100");
        assertThat(point.get("likes").asText()).isEqualTo("0");
        assertThat(point.get("commentCount").isNull()).isTrue();
        assertThat(point.get("viewsDelta").isNull()).isTrue();
        assertThat(get(b, path).statusCode()).isEqualTo(404);
        assertThat(get(b, "/api/metrics/works?accountId=" + accountId).statusCode()).isEqualTo(404);
        assertThat(get(client(), path).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(HttpClient client, String action, String csv, boolean csrf) throws Exception {
        var boundary = "metrics-test-boundary";
        var body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"metrics.csv\""
                + "\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        return send(client, "/api/metrics/import/" + action,
                "multipart/form-data; boundary=" + boundary, body, csrf);
    }

    private HttpResponse<String> send(HttpClient client, String path, String contentType,
                                      String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType);
        if (csrf) {
            var token = json.readTree(client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            request.header(token.get("headerName").asText(), token.get("token").asText());
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void login(HttpClient client, String email) throws Exception {
        assertThat(send(client, "/api/auth/login", "application/x-www-form-urlencoded",
                "email=" + email + "&password=development-fixture-only", true).statusCode()).isEqualTo(204);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
