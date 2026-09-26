package com.example.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
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
class AccountIsolationTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;

    @Test
    void twoProfilesCanBeEditedButAnotherUserCannotReadOrEditThem() throws Exception {
        var a = client();
        var b = client();
        login(a, "creator-a@example.test");
        login(b, "creator-b@example.test");

        var java = create(a, "Java 与托福", "程序员", 3);
        var test = create(a, "测试账号", "普通用户", 1);
        assertThat(java.statusCode()).isEqualTo(201);
        assertThat(test.statusCode()).isEqualTo(201);
        var javaId = json.readTree(java.body()).get("id").asText();
        var testId = json.readTree(test.body()).get("id").asText();
        assertThat(javaId).isNotEqualTo(testId);
        assertThat(get(a, "/api/accounts").body()).contains(javaId, testId);
        assertThat(get(b, "/api/accounts").body()).doesNotContain(javaId, testId);
        assertThat(get(b, "/api/accounts/" + javaId).statusCode()).isEqualTo(404);

        var changed = json.createObjectNode().put("name", "Java 八股与托福")
                .put("audience", "程序员").put("positioning", "面试快问快答与托福跟读")
                .put("weeklyTarget", 3).set("columns", json.createArrayNode().add("Java 面试").add("托福跟读"));
        assertThat(put(b, javaId, changed).statusCode()).isEqualTo(404);
        assertThat(put(a, javaId, changed).statusCode()).isEqualTo(200);
        assertThat(get(a, "/api/accounts/" + javaId).body()).contains("Java 八股与托福", "托福跟读");
        assertThat(get(b, "/api/agent/accounts").body()).doesNotContain(javaId);
    }

    @Test
    void invalidAccountCannotBeSavedAndAnonymousCannotList() throws Exception {
        var a = client();
        assertThat(get(a, "/api/accounts").statusCode()).isEqualTo(401);
        login(a, "creator-a@example.test");
        var invalid = json.createObjectNode().put("name", " ").put("audience", "程序员")
                .put("positioning", "内容").put("weeklyTarget", 0)
                .set("columns", json.createArrayNode());
        assertThat(post(a, "/api/accounts", "application/json", invalid.toString()).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> create(HttpClient client, String name, String audience, int weeklyTarget) throws Exception {
        var body = json.createObjectNode().put("name", name).put("audience", audience)
                .put("positioning", "面向程序员的短视频").put("weeklyTarget", weeklyTarget)
                .set("columns", json.createArrayNode().add("Java 面试").add("托福跟读"));
        return post(client, "/api/accounts", "application/json", body.toString());
    }

    private void login(HttpClient client, String email) throws Exception {
        var form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=development-fixture-only";
        assertThat(post(client, "/api/auth/login", "application/x-www-form-urlencoded", form).statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> put(HttpClient client, String id, JsonNode body) throws Exception {
        var csrf = csrf(client);
        return client.send(HttpRequest.newBuilder(uri("/api/accounts/" + id))
                .header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String type, String body) throws Exception {
        var csrf = csrf(client);
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", type)
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode csrf(HttpClient client) throws Exception { return json.readTree(get(client, "/api/auth/csrf").body()); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
