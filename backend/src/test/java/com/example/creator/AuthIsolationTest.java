package com.example.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.redisson.api.RedissonClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class AuthIsolationTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private RedissonClient redisson;
    private static final String PASSWORD = "Test-only-password-2026";

    @Test
    void anonymousRequestsCannotChooseAnIdentity() throws Exception {
        assertThat(get(client(), "/api/auth/me?userId=1").statusCode()).isEqualTo(401);
    }

    @Test
    void developmentUsersArePersistedWithHashedPasswordsAndCanBothLogin() throws Exception {
        var a = client();
        var b = client();
        assertThat(login(a, "creator-a@example.test", "development-fixture-only").statusCode()).isEqualTo(204);
        assertThat(login(b, "creator-b@example.test", "development-fixture-only").statusCode()).isEqualTo(204);
        var hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class,
                "creator-a@example.test");
        assertThat(hash).isNotEqualTo("development-fixture-only");
        assertThat(encoder.matches("development-fixture-only", hash)).isTrue();
        assertThat(json.readTree(get(a, "/api/auth/me").body()).get("id"))
                .isNotEqualTo(json.readTree(get(b, "/api/auth/me").body()).get("id"));
    }

    @Test
    void separateSessionsKeepTheirOwnIdentityDespiteSuppliedUserId() throws Exception {
        var emailA = createUser();
        var emailB = createUser();
        var clientA = client();
        var clientB = client();
        assertThat(login(clientA, emailA, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(login(clientB, emailB, PASSWORD).statusCode()).isEqualTo(204);
        var userB = json.readTree(get(clientB, "/api/auth/me").body());
        var responseA = get(clientA, "/api/auth/me?userId=" + userB.get("id").asLong());
        assertThat(responseA.statusCode()).isEqualTo(200);
        var userA = json.readTree(responseA.body());
        assertThat(userA.get("id").asLong()).isNotEqualTo(userB.get("id").asLong());
        assertThat(userA.get("email").asText()).isEqualTo(emailA);
        assertThat(userB.get("email").asText()).isEqualTo(emailB);
        assertThat(userA.has("passwordHash")).isFalse();
        assertThat(redisson.getKeys().countExists("creator:sessions:sessions:" + sessionId(clientA)))
                .isEqualTo(1);
    }

    @Test
    void wrongPasswordDoesNotCreateAnAuthenticatedSession() throws Exception {
        var client = client();
        var response = login(client, createUser(), "wrong-password");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void loginAndLogoutRequireCsrfTokens() throws Exception {
        var client = client();
        var email = createUser();
        assertThat(post(client, "/api/auth/login", form(email, PASSWORD), null).statusCode()).isEqualTo(403);
        assertThat(login(client, email, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(post(client, "/api/auth/logout", "", null).statusCode()).isEqualTo(403);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void loginRotatesSessionAndLogoutInvalidatesReplayedCookie() throws Exception {
        var client = client();
        var token = csrf(client);
        var beforeLogin = cookie(client);
        var response = post(client, "/api/auth/login", form(createUser(), PASSWORD), token);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(cookie(client)).isNotEqualTo(beforeLogin);
        assertThat(response.headers().allValues("set-cookie").toString())
                .contains("HttpOnly").contains("SameSite=Lax");
        var authenticatedCookie = cookie(client);
        assertThat(post(client, "/api/auth/logout", "", csrf(client)).statusCode()).isEqualTo(204);
        var replay = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/auth/me"))
                .header("Cookie", authenticatedCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
    }

    private String createUser() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)",
                email, encoder.encode(PASSWORD), "隔离测试");
        return email;
    }

    private HttpResponse<String> login(HttpClient client, String email, String password) throws Exception {
        return post(client, "/api/auth/login", form(email, password), csrf(client));
    }

    private String form(String email, String password) {
        return "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
    }

    private JsonNode csrf(HttpClient client) throws Exception {
        var response = get(client, "/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private String cookie(HttpClient client) {
        var cookies = (CookieManager) client.cookieHandler().orElseThrow();
        var session = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("CREATOR_SESSION")).findFirst().orElseThrow();
        return session.getName() + "=" + session.getValue();
    }

    private String sessionId(HttpClient client) {
        var encoded = cookie(client).substring("CREATOR_SESSION=".length());
        return new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) { request.header(csrf.get("headerName").asText(), csrf.get("token").asText()); }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
