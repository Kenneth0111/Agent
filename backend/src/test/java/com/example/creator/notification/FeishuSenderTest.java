package com.example.creator.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuSenderTest {
    private final SecretCipher cipher = new SecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final NotificationService notifications = mock(NotificationService.class);
    private final HttpClient client = mock(HttpClient.class);
    private final FeishuSender feishu = new FeishuSender(notifications, cipher,
            new ObjectMapper(), "https://creator.example.test/", client);

    @Test
    void signsSummaryAndRecordsAcceptedResponse() throws Exception {
        assertThat(FeishuSender.sign(1700000000L, "example-secret"))
                .isEqualTo("Gs3YRutIJpVN2FvEXKXHU6bEj2pVdSriQ0niig9EbUk=");
        var payload = new ObjectMapper().readTree(feishu.payload(1700000000L,
                "example-secret", "run-1"));
        assertThat(payload.get("timestamp").asLong()).isEqualTo(1700000000L);
        assertThat(payload.get("sign").asText()).isEqualTo("Gs3YRutIJpVN2FvEXKXHU6bEj2pVdSriQ0niig9EbUk=");
        assertThat(payload.get("msg_type").asText()).isEqualTo("text");
        assertThat(payload.get("content").get("text").asText())
                .contains("https://creator.example.test/?run=run-1")
                .doesNotContain("example-secret", "test-token");
        when(notifications.claimFeishu(7, "trigger-1")).thenReturn(Optional.of(delivery()));
        var response = response(200, "{\"code\":0}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        feishu.deliverPending(7, "trigger-1");
        verify(notifications).finishFeishu(7, "delivery-1", "SENT", null);
    }

    @Test
    void rejectsProviderSignatureErrorWithoutLeakingResponse() throws Exception {
        when(notifications.claimFeishu(7, "trigger-1")).thenReturn(Optional.of(delivery()));
        var response = response(200, "{\"code\":19021,\"msg\":\"secret-details\"}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        feishu.deliverPending(7, "trigger-1");
        verify(notifications).finishFeishu(7, "delivery-1", "FAILED", "FEISHU_REJECTED");
    }

    @Test
    void recordsServerFailureAsUnknown() throws Exception {
        when(notifications.claimFeishu(7, "trigger-1")).thenReturn(Optional.of(delivery()));
        var response = response(503, "unavailable");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        feishu.deliverPending(7, "trigger-1");
        verify(notifications).finishFeishu(7, "delivery-1", "UNKNOWN", "FEISHU_SEND_UNKNOWN");
    }

    @Test
    void schedulesRetryOnlyForExplicitRateLimit() throws Exception {
        when(notifications.claimFeishu(7, "trigger-1")).thenReturn(Optional.of(delivery()));
        var response = response(429, "rate limited");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        feishu.deliverPending(7, "trigger-1");
        verify(notifications).finishFeishu(7, "delivery-1", "RETRY", "FEISHU_RATE_LIMITED");
    }

    @Test
    void rejectsUnsupportedWebhookBeforeNetworkCall() {
        assertThat(NotificationService.validFeishuWebhook(
                "https://open.feishu.cn/open-apis/bot/v2/hook/test-token")).isTrue();
        assertThat(NotificationService.validFeishuWebhook(
                "https://open.feishu.cn.evil.test/open-apis/bot/v2/hook/test-token")).isFalse();
        assertThat(NotificationService.validFeishuWebhook(
                "https://open.feishu.cn/open-apis/bot/v2/hook/test-token?redirect=1")).isFalse();
        assertThat(NotificationService.validFeishuWebhook(
                "https://open.feishu.cn/open-apis/bot/v2/hook/")).isFalse();
        when(notifications.claimFeishu(7, "trigger-1")).thenReturn(Optional.of(
                new NotificationService.FeishuDelivery("delivery-1", "run-1",
                        cipher.encrypt("https://example.test/hook"), cipher.encrypt("sign-secret"))));
        feishu.deliverPending(7, "trigger-1");
        verify(notifications).finishFeishu(7, "delivery-1", "FAILED", "FEISHU_WEBHOOK_INVALID");
    }

    private NotificationService.FeishuDelivery delivery() {
        return new NotificationService.FeishuDelivery("delivery-1", "run-1",
                cipher.encrypt("https://open.feishu.cn/open-apis/bot/v2/hook/test-token"),
                cipher.encrypt("sign-secret"));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
