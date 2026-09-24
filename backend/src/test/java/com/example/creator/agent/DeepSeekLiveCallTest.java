package com.example.creator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that spends real money, and only when a key is present in the environment; every
 * other model test uses a local stub. Skipped runs are not evidence that the provider works.
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekLiveCallTest {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekLiveCallTest.class);

    private final ModelGateway gateway = new ModelGateway(ModelConfig.createModel(
            environment("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
            System.getenv("DEEPSEEK_API_KEY"),
            environment("DEEPSEEK_MODEL", "deepseek-chat"),
            Duration.ofSeconds(30)), new ObjectMapper());

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void returnsAShortTextReply() {
        var reply = gateway.reply("用一句中文说明什么是 JVM 的垃圾回收。");
        assertThat(reply).isNotBlank();
        log.info("live text reply: chars={} preview={}", reply.length(), preview(reply));
    }

    @Test
    void returnsParsableStructuredContent() {
        var draft = gateway.interviewDraft("HashMap 与 ConcurrentHashMap 的区别");
        assertThat(draft.question()).isNotBlank();
        assertThat(draft.answer()).isNotBlank();
        log.info("live structured reply: question={} answerChars={}", preview(draft.question()),
                draft.answer().length());
    }

    /** Keeps the log short; the key is never part of a reply and is never logged. */
    private static String preview(String text) {
        var single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= 60 ? single : single.substring(0, 60) + "…";
    }
}
