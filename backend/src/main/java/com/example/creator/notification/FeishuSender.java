package com.example.creator.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FeishuSender {
    private static final Logger log = LoggerFactory.getLogger(FeishuSender.class);
    private final NotificationService notifications;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final HttpClient client;
    private final String publicUrl;

    @Autowired
    FeishuSender(NotificationService notifications, SecretCipher cipher, ObjectMapper json,
                 @Value("${creator.notification.public-url}") String publicUrl) {
        this(notifications, cipher, json, publicUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    FeishuSender(NotificationService notifications, SecretCipher cipher, ObjectMapper json,
                 String publicUrl, HttpClient client) {
        this.notifications = notifications;
        this.cipher = cipher;
        this.json = json;
        this.client = client;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
    }

    public void deliverPending(long ownerId, String triggerId) {
        var pending = notifications.pendingFeishu(ownerId, triggerId);
        if (pending.isEmpty()) return;
        var delivery = pending.get();
        try {
            var webhook = cipher.decrypt(delivery.webhookEnc());
            if (!NotificationService.validFeishuWebhook(webhook)) {
                finish(ownerId, delivery.id(), "FAILED", "FEISHU_WEBHOOK_INVALID");
                return;
            }
            var timestamp = Instant.now().getEpochSecond();
            var body = payload(timestamp, cipher.decrypt(delivery.secretEnc()), delivery.runId());
            var request = HttpRequest.newBuilder(URI.create(webhook))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 408 || response.statusCode() == 429
                    || response.statusCode() >= 500) {
                finish(ownerId, delivery.id(), "UNKNOWN", "FEISHU_SEND_UNKNOWN");
            } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                finish(ownerId, delivery.id(), "FAILED", "FEISHU_REJECTED");
            } else {
                finishFromBody(ownerId, delivery.id(), response.body());
            }
        } catch (SecretCipher.KeyUnavailable missing) {
            finish(ownerId, delivery.id(), "FAILED", "NOTIFICATION_KEY_UNAVAILABLE");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            finish(ownerId, delivery.id(), "FAILED", "NOTIFICATION_SECRET_INVALID");
        } catch (JsonProcessingException invalid) {
            finish(ownerId, delivery.id(), "FAILED", "FEISHU_PAYLOAD_INVALID");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(ownerId, delivery.id(), "UNKNOWN", "FEISHU_SEND_UNKNOWN");
        } catch (IOException uncertain) {
            finish(ownerId, delivery.id(), "UNKNOWN", "FEISHU_SEND_UNKNOWN");
        }
    }

    String payload(long timestamp, String secret, String runId) throws JsonProcessingException {
        return json.writeValueAsString(Map.of("timestamp", timestamp, "sign", sign(timestamp, secret),
                "msg_type", "text", "content", Map.of("text",
                        "本周草稿已生成。请登录工作台查看并审阅：\n" + publicUrl + "?run=" + runId)));
    }

    static String sign(long timestamp, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void finishFromBody(long ownerId, String id, String body) {
        try {
            var result = json.readTree(body);
            var code = result.has("code") ? result.get("code") : result.get("StatusCode");
            if (code == null || !code.isIntegralNumber()) {
                finish(ownerId, id, "UNKNOWN", "FEISHU_RESPONSE_UNKNOWN");
            } else if (code.intValue() == 0) {
                finish(ownerId, id, "SENT", null);
            } else {
                finish(ownerId, id, "FAILED", "FEISHU_REJECTED");
            }
        } catch (JsonProcessingException invalid) {
            finish(ownerId, id, "UNKNOWN", "FEISHU_RESPONSE_UNKNOWN");
        }
    }

    private void finish(long ownerId, String id, String status, String code) {
        notifications.finishFeishu(ownerId, id, status, code);
        if (code != null) log.warn("Feishu delivery {} finished: status={} code={}", id, status, code);
    }
}
