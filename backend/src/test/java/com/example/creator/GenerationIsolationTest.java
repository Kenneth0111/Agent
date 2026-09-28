package com.example.creator;

import com.example.creator.account.AccountService;
import com.example.creator.content.ContentService;
import com.example.creator.content.ContentValidator.Script;
import com.example.creator.content.ContentValidator.Topic;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class GenerationIsolationTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ContentService content;

    @Test
    void generatedDraftRoutesRequireOwnershipForReadAndRevision() throws Exception {
        var owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, "creator-a@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("HTTP 草稿", "程序员",
                "Java 面试", List.of("Java 面试"), 2));
        var topicId = content.saveTopic(owner, account.id(), new Topic("Java 面试", "并发", "程序员",
                "可见性", "问题", "简答", List.of("source-1"), "资料"), Set.of("source-1"));
        var scriptId = content.saveScript(owner, topicId,
                new Script("volatile 保证可见性。", "口播", List.of("source-1")), Set.of("source-1"));

        var a = client();
        var b = client();
        assertThat(get(b, "/api/generations/scripts/" + scriptId).statusCode()).isEqualTo(401);
        login(a, "creator-a@example.test");
        login(b, "creator-b@example.test");
        assertThat(get(a, "/api/generations/topics?accountId=" + account.id()).body()).contains(topicId);
        assertThat(get(a, "/api/generations/scripts?topicId=" + topicId).body()).contains(scriptId);
        assertThat(get(a, "/api/generations/scripts/" + scriptId + "/versions").statusCode()).isEqualTo(200);
        assertThat(get(b, "/api/generations/topics?accountId=" + account.id()).statusCode()).isEqualTo(404);
        assertThat(get(b, "/api/generations/scripts?topicId=" + topicId).statusCode()).isEqualTo(404);
        assertThat(get(b, "/api/generations/topics/" + topicId).statusCode()).isEqualTo(404);
        assertThat(get(b, "/api/generations/scripts/" + scriptId).statusCode()).isEqualTo(404);
        assertThat(get(b, "/api/generations/scripts/" + scriptId + "/versions").statusCode()).isEqualTo(404);
        assertThat(post(b, "/api/generations/scripts/" + scriptId + "/revise",
                "{\"expectedVersion\":1,\"instruction\":\"改口播\"}").statusCode()).isEqualTo(404);
        var edit = "{\"expectedVersion\":1,\"spokenText\":\"更准确的口播稿\",\"shootingNotes\":\"录屏\"}";
        assertThat(put(b, "/api/generations/scripts/" + scriptId, edit).statusCode()).isEqualTo(404);
        var saved = put(a, "/api/generations/scripts/" + scriptId, edit);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).get("version").asInt()).isEqualTo(2);
        assertThat(json.readTree(saved.body()).at("/script/sourceIds/0").asText()).isEqualTo("source-1");
        assertThat(put(a, "/api/generations/scripts/" + scriptId, edit).statusCode()).isEqualTo(409);
        assertThat(post(a, "/api/generations/scripts/" + scriptId + "/confirm",
                "{\"expectedVersion\":2}").statusCode()).isEqualTo(200);
        assertThat(put(a, "/api/generations/scripts/" + scriptId,
                "{\"expectedVersion\":3,\"spokenText\":\"确认后禁止编辑\",\"shootingNotes\":\"录屏\"}")
                .statusCode()).isEqualTo(409);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        var csrf = json.readTree(get(client, "/api/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(HttpClient client, String path, String body) throws Exception {
        var csrf = json.readTree(get(client, "/api/auth/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void login(HttpClient client, String email) throws Exception {
        var csrf = json.readTree(get(client, "/api/auth/csrf").body());
        var form = "email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=development-fixture-only";
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
    }
}
