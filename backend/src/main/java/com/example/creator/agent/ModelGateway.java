package com.example.creator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelGateway {
    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);
    private static final String DRAFT_INSTRUCTION = """
            你是 Java 面试内容助手。只输出一个 JSON 对象，不要输出其他文字。
            格式：{"question": "面试问题", "answer": "不超过 80 字的简答"}""";

    private final ChatModel model;
    private final ObjectMapper json;

    public ModelGateway(ChatModel model, ObjectMapper json) {
        this.model = model;
        this.json = json;
    }

    public String reply(String prompt) {
        var text = call("reply", ChatRequest.builder().messages(UserMessage.from(prompt)).build());
        if (text == null || text.isBlank()) throw new ModelFailure("MODEL_INVALID_OUTPUT");
        return text;
    }

    public InterviewDraft interviewDraft(String topic) {
        var text = call("interviewDraft", ChatRequest.builder()
                .messages(SystemMessage.from(DRAFT_INSTRUCTION), UserMessage.from("主题：" + topic))
                .responseFormat(ResponseFormat.JSON).build());
        JsonNode node;
        try {
            node = json.readTree(text == null ? "" : text);
        } catch (JsonProcessingException invalid) {
            throw new ModelFailure("MODEL_INVALID_OUTPUT");
        }
        return new InterviewDraft(requiredText(node, "question"), requiredText(node, "answer"));
    }

    /**
     * Lets the model call the given read-only tools, at most {@code maxToolRounds} times, before it
     * must answer in text. Exceeding the bound fails the run instead of continuing to spend calls.
     */
    public String replyUsingTools(String operation, String systemPrompt, String userPrompt,
                                  List<LocalTool> tools, int maxToolRounds) {
        var byName = tools.stream().collect(Collectors.toMap(LocalTool::name, Function.identity()));
        var specifications = tools.stream().map(LocalTool::specification).toList();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)));
        for (int round = 0; ; round++) {
            var answer = send(operation, ChatRequest.builder()
                    .messages(messages).toolSpecifications(specifications).build()).aiMessage();
            if (answer == null || !answer.hasToolExecutionRequests()) {
                var text = answer == null ? null : answer.text();
                if (text == null || text.isBlank()) throw new ModelFailure("MODEL_INVALID_OUTPUT");
                return text;
            }
            if (round == maxToolRounds) {
                log.warn("agent run {} stopped: code=AGENT_TOOL_LIMIT rounds={}", operation, round);
                throw new ModelFailure("AGENT_TOOL_LIMIT");
            }
            messages.add(answer);
            answer.toolExecutionRequests().forEach(request ->
                    messages.add(ToolExecutionResultMessage.from(request, execute(byName, request))));
        }
    }

    private static String execute(Map<String, LocalTool> byName, ToolExecutionRequest request) {
        var tool = byName.get(request.name());
        long started = System.nanoTime();
        if (tool == null) {
            log.warn("tool {} rejected: status=UNKNOWN_TOOL durationMs={}", request.name(),
                    elapsedMillis(started));
            return "UNKNOWN_TOOL";
        }
        try {
            var result = tool.execute(request.arguments());
            log.info("tool {} finished: status=OK durationMs={}", tool.name(), elapsedMillis(started));
            return result;
        } catch (LocalTool.ToolRejection rejection) {
            log.warn("tool {} rejected: status={} durationMs={}", tool.name(), rejection.getMessage(),
                    elapsedMillis(started));
            return rejection.getMessage();
        }
    }

    private String call(String operation, ChatRequest request) {
        var answer = send(operation, request).aiMessage();
        return answer == null ? null : answer.text();
    }

    private ChatResponse send(String operation, ChatRequest request) {
        if (model == null) throw new ModelFailure("MODEL_NOT_CONFIGURED");
        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.chat(request);
        } catch (RuntimeException failure) {
            var code = failureCode(failure);
            log.warn("model call {} failed: code={} type={} durationMs={}", operation, code,
                    failure.getClass().getSimpleName(), elapsedMillis(started));
            throw new ModelFailure(code);
        }
        var usage = response.tokenUsage();
        log.info("model call {} succeeded: durationMs={} inputTokens={} outputTokens={}", operation,
                elapsedMillis(started), usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount());
        return response;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ModelFailure("MODEL_INVALID_OUTPUT");
        }
        return value.asText().strip();
    }

    private static String failureCode(Throwable failure) {
        if (failure instanceof AuthenticationException) return "MODEL_AUTH_FAILED";
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException) return "MODEL_TIMEOUT";
        }
        return "MODEL_UPSTREAM_FAILED";
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    public record InterviewDraft(String question, String answer) { }

    public static final class ModelFailure extends RuntimeException {
        ModelFailure(String code) {
            super(code, null, false, false);
        }
    }
}
