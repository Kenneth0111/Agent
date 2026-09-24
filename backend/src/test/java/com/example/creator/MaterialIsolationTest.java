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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
class MaterialIsolationTest extends IntegrationTestSupport {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void importedChineseTextCanBePreviewedAndDeletedOnlyByItsOwner() throws Exception {
        var owner = client();
        var stranger = client();
        login(owner, "creator-a@example.test");
        login(stranger, "creator-b@example.test");
        var source = "Java 的 volatile 保证可见性。\n\n并发编程中还要理解 happens-before。";
        var created = post(owner, material("并发笔记", source, "notes.md"));
        assertThat(created.statusCode()).isEqualTo(201);
        var id = json.readTree(created.body()).get("id").asText();
        assertThat(get(owner, "/api/materials/" + id).body()).contains("volatile", "并发编程", "https://example.test/notes");
        assertThat(get(owner, "/api/materials").body()).contains(id);
        assertThat(get(stranger, "/api/materials").body()).doesNotContain(id);
        assertThat(get(stranger, "/api/materials/" + id).statusCode()).isEqualTo(404);
        assertThat(delete(stranger, id).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_segments WHERE material_id = ?", Integer.class, id))
                .isGreaterThan(0);
        assertThat(delete(owner, id).statusCode()).isEqualTo(204);
        assertThat(get(owner, "/api/materials/" + id).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material_segments WHERE material_id = ?", Integer.class, id))
                .isZero();

        var pasted = json.createObjectNode().put("title", "跟读记录").put("purpose", "托福跟读")
                .putNull("sourceUrl").putNull("fileName").put("content", "这是一段直接粘贴的跟读材料。");
        assertThat(post(owner, pasted.toString()).statusCode()).isEqualTo(201);
    }

    @Test
    void emptyOversizedAndUnsupportedFilesAreRejected() throws Exception {
        var owner = client();
        assertThat(get(owner, "/api/materials").statusCode()).isEqualTo(401);
        login(owner, "creator-a@example.test");
        assertThat(post(owner, material("空材料", " \n ", "empty.txt")).statusCode()).isEqualTo(400);
        assertThat(post(owner, material("超限材料", "a".repeat(102_401), "large.txt")).statusCode()).isEqualTo(413);
        assertThat(post(owner, material("错误类型", "valid text", "image.png")).statusCode()).isEqualTo(400);
    }

    @Test
    void linkExcerptAndPdfStayWithOwnedAccounts() throws Exception {
        var owner = client();
        var stranger = client();
        login(owner, "creator-a@example.test");
        login(stranger, "creator-b@example.test");
        var ownAccount = createAccount(owner);
        var foreignAccount = createAccount(stranger);

        var link = json.createObjectNode().put("title", "官网摘录").put("purpose", "Java 面试")
                .put("kind", "LINK").put("sourceUrl", "https://127.0.0.1:1/unreachable")
                .put("content", "用户自己填写的摘录，不抓取网页。");
        link.putArray("accountIds").add(ownAccount);
        var created = post(owner, link.toString());
        assertThat(created.statusCode()).isEqualTo(201);
        var linkId = json.readTree(created.body()).get("id").asText();
        assertThat(created.body()).contains("LINK", "用户自己填写的摘录", ownAccount);
        assertThat(get(stranger, "/api/materials/" + linkId).statusCode()).isEqualTo(404);

        link.putArray("accountIds").add(foreignAccount);
        var rejected = post(owner, link.toString());
        assertThat(rejected.statusCode()).isEqualTo(404);
        assertThat(rejected.body()).contains("ACCOUNT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM materials WHERE owner_id ="
                + " (SELECT owner_id FROM content_accounts WHERE id = ?) AND title = ?", Integer.class,
                ownAccount, "官网摘录")).isEqualTo(1);

        var pdf = postPdf(owner, pdfBytes(true), ownAccount);
        assertThat(pdf.statusCode()).isEqualTo(201);
        assertThat(pdf.body()).contains("PDF", "Java interview notes", ownAccount);
        var pdfId = json.readTree(pdf.body()).get("id").asText();
        assertThat(get(stranger, "/api/materials/" + pdfId).statusCode()).isEqualTo(404);
        assertThat(postPdf(owner, pdfBytes(false), ownAccount).body()).contains("PDF_TEXT_UNAVAILABLE");
    }

    private byte[] pdfBytes(boolean withText) throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (withText) {
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(60, 700);
                    stream.showText("Java interview notes");
                    stream.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private String createAccount(HttpClient client) throws Exception {
        var body = json.createObjectNode().put("name", "Interview account").put("audience", "Developers")
                .put("positioning", "Interview answers").put("weeklyTarget", 3);
        body.putArray("columns").add("Java");
        var token = csrf(client);
        var response = client.send(HttpRequest.newBuilder(uri("/api/accounts"))
                .header("Content-Type", "application/json")
                .header(token.get("headerName").asText(), token.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    private HttpResponse<String> postPdf(HttpClient client, byte[] pdf, String accountId) throws Exception {
        var boundary = "material-test-boundary";
        var body = new ByteArrayOutputStream();
        for (var field : new String[][] {{"title", "PDF notes"}, {"purpose", "Interview"},
                {"accountIds", accountId}}) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0]
                    + "\"\r\n\r\n" + field[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"notes.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(pdf);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var token = csrf(client);
        return client.send(HttpRequest.newBuilder(uri("/api/materials/pdf"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header(token.get("headerName").asText(), token.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String material(String title, String content, String fileName) {
        return json.createObjectNode().put("title", title).put("purpose", "Java 面试")
                .put("sourceUrl", "https://example.test/notes")
                .put("fileName", fileName).put("content", content).toString();
    }

    private void login(HttpClient client, String email) throws Exception {
        var form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=development-fixture-only";
        var token = csrf(client);
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(token.get("headerName").asText(), token.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> post(HttpClient client, String body) throws Exception {
        var token = csrf(client);
        return client.send(HttpRequest.newBuilder(uri("/api/materials"))
                .header("Content-Type", "application/json")
                .header(token.get("headerName").asText(), token.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(HttpClient client, String id) throws Exception {
        var token = csrf(client);
        return client.send(HttpRequest.newBuilder(uri("/api/materials/" + id))
                .header(token.get("headerName").asText(), token.get("token").asText())
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode csrf(HttpClient client) throws Exception { return json.readTree(get(client, "/api/auth/csrf").body()); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
