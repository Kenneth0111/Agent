package com.example.creator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private OpenAiStubServer provider;

    @BeforeEach
    void startProvider() throws Exception {
        provider = new OpenAiStubServer();
    }

    @AfterEach
    void stopProvider() {
        provider.close();
    }

    private ModelGateway gateway(Duration timeout) {
        return new ModelGateway(ModelConfig.createModel(provider.baseUrl(), "test-only-key",
                "test-model", timeout), json);
    }

    @Test
    void readsTextFromAnOpenAiCompatibleResponse() {
        assertThat(gateway(Duration.ofSeconds(2)).reply("打个招呼")).isEqualTo("回答示例");
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void validatesRequiredFieldsOfStructuredContent() {
        provider.alwaysReplyWith("{\"question\":\"Java 的接口是什么？\",\"answer\":\"一种类型契约。\"}");
        var draft = gateway(Duration.ofSeconds(2)).interviewDraft("接口");
        assertThat(draft.question()).isEqualTo("Java 的接口是什么？");
        assertThat(draft.answer()).isEqualTo("一种类型契约。");
    }

    @Test
    void rejectsInvalidJsonAndMissingFields() {
        for (var invalid : new String[]{"不是 JSON", "{}", "{\"question\":\"测试\",\"answer\":\" \"}"}) {
            provider.alwaysReplyWith(invalid);
            assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).interviewDraft("接口"))
                    .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_INVALID_OUTPUT");
        }
    }

    @Test
    void rejectsInvalidCredentialsWithoutLeakingProviderDetailsOrRetrying() {
        provider.failWithStatus(401);
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_AUTH_FAILED");
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void upstreamFailuresAreNotSuccessfulRepliesAndAreNotRetried() {
        provider.failWithStatus(500);
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_UPSTREAM_FAILED");
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void providerTimeoutIsBounded() {
        provider.delayEachReply(Duration.ofSeconds(2));
        assertThatThrownBy(() -> gateway(Duration.ofMillis(150)).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_TIMEOUT");
    }

    @Test
    void missingCredentialsProduceAnExplicitUnavailableResultWithoutNetworkCalls() {
        assertThatThrownBy(() -> new ModelGateway(null, json).reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_NOT_CONFIGURED");
        assertThat(provider.calls()).isZero();
    }
}
