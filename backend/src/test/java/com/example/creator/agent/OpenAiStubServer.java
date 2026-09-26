package com.example.creator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local OpenAI-compatible endpoint: model behaviour is scripted, so no paid calls happen in tests. */
final class OpenAiStubServer implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final Deque<Map<String, Object>> scriptedMessages = new ArrayDeque<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ExecutorService executor;

    private int status = 200;
    private long delayMillis;
    private Map<String, Object> repeatedMessage = assistantText("回答示例");

    OpenAiStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                byte[] body = status == 200 ? json.writeValueAsBytes(completion(nextMessage()))
                        : "{\"error\":{\"message\":\"upstream secret details\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int calls() {
        return requestBodies.size();
    }

    /** Request bodies exactly as received, used to assert what was and was not sent upstream. */
    List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    void alwaysReplyWith(String text) {
        repeatedMessage = assistantText(text);
    }

    void alwaysRequestTool(String toolName, String argumentsJson) {
        repeatedMessage = assistantToolCall("call-repeat", toolName, argumentsJson);
    }

    void thenReplyWith(String text) {
        scriptedMessages.add(assistantText(text));
    }

    void thenRequestTool(String toolName, String argumentsJson) {
        scriptedMessages.add(assistantToolCall("call-" + (scriptedMessages.size() + 1), toolName, argumentsJson));
    }

    void failWithStatus(int httpStatus) {
        status = httpStatus;
    }

    void delayEachReply(Duration delay) {
        delayMillis = delay.toMillis();
    }

    private Map<String, Object> nextMessage() {
        var scripted = scriptedMessages.poll();
        return scripted == null ? repeatedMessage : scripted;
    }

    private static Map<String, Object> assistantText(String text) {
        return Map.of("role", "assistant", "content", text);
    }

    private static Map<String, Object> assistantToolCall(String id, String toolName, String argumentsJson) {
        return Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                "id", id, "type", "function",
                "function", Map.of("name", toolName, "arguments", argumentsJson))));
    }

    private Map<String, Object> completion(Map<String, Object> message) {
        var finishReason = message.containsKey("tool_calls") ? "tool_calls" : "stop";
        return Map.of("id", "test-response", "object", "chat.completion", "model", "test-model",
                "choices", List.of(Map.of("index", 0, "finish_reason", finishReason, "message", message)),
                "usage", Map.of("prompt_tokens", 5, "completion_tokens", 3, "total_tokens", 8));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
