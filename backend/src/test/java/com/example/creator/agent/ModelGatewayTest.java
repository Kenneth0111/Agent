package com.example.creator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private int status = 200;
    private String content = "回答示例";
    private long delayMillis;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                byte[] body = status == 200 ? json.writeValueAsBytes(Map.of(
                        "id", "test-response", "object", "chat.completion", "model", "test-model",
                        "choices", new Object[]{Map.of("index", 0, "finish_reason", "stop",
                                "message", Map.of("role", "assistant", "content", content))},
                        "usage", Map.of("prompt_tokens", 5, "completion_tokens", 3, "total_tokens", 8)))
                        : "{\"error\":{\"message\":\"upstream secret details\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach
    void stopServer() { server.stop(0); executor.shutdownNow(); }

    private ModelGateway gateway(Duration timeout) {
        return new ModelGateway(ModelConfig.createModel("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-only-key", "test-model", timeout), json);
    }

    @Test
    void readsTextFromAnOpenAiCompatibleResponse() {
        assertThat(gateway(Duration.ofSeconds(2)).reply("打个招呼")).isEqualTo("回答示例");
        assertThat(calls).hasValue(1);
    }

    @Test
    void validatesRequiredFieldsOfStructuredContent() {
        content = "{\"question\":\"Java 的接口是什么？\",\"answer\":\"一种类型契约。\"}";
        var draft = gateway(Duration.ofSeconds(2)).interviewDraft("接口");
        assertThat(draft.question()).isEqualTo("Java 的接口是什么？");
        assertThat(draft.answer()).isEqualTo("一种类型契约。");
    }

    @Test
    void rejectsInvalidJsonAndMissingFields() {
        for (var invalid : new String[]{"不是 JSON", "{}", "{\"question\":\"测试\",\"answer\":\" \"}"}) {
            content = invalid;
            assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).interviewDraft("接口"))
                    .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_INVALID_OUTPUT");
        }
    }

    @Test
    void rejectsInvalidCredentialsWithoutLeakingProviderDetailsOrRetrying() {
        status = 401;
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_AUTH_FAILED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void upstreamFailuresAreNotSuccessfulRepliesAndAreNotRetried() {
        status = 500;
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_UPSTREAM_FAILED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void providerTimeoutIsBounded() {
        delayMillis = 2000;
        assertThatThrownBy(() -> gateway(Duration.ofMillis(150)).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_TIMEOUT");
    }

    @Test
    void missingCredentialsProduceAnExplicitUnavailableResultWithoutNetworkCalls() {
        assertThatThrownBy(() -> new ModelGateway(null, json).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_NOT_CONFIGURED");
        assertThat(calls).hasValue(0);
    }
}
