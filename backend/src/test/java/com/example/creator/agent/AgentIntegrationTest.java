package com.example.creator.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentIntegrationTest {
    private static final long OWNER = 7L;
    private static final long OTHER_USER = 8L;
    private static final String OWNED_ACCOUNT = "sample-7-java";
    private static final String OTHER_ACCOUNT = "sample-8-java";

    private final ObjectMapper json = new ObjectMapper();
    private final AccountProfiles accounts = new SampleAccountProfiles();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private OpenAiStubServer provider;
    private ContentWorkflow workflow;

    @BeforeEach
    void startProvider() throws Exception {
        provider = new OpenAiStubServer();
        var gateway = new ModelGateway(ModelConfig.createModel(provider.baseUrl(), "test-only-key",
                "test-model", Duration.ofSeconds(2)), json);
        workflow = new ContentWorkflow(gateway, accounts, json);
        logs.start();
        ((Logger) LoggerFactory.getLogger(ModelGateway.class)).addAppender(logs);
    }

    @AfterEach
    void stopProvider() {
        ((Logger) LoggerFactory.getLogger(ModelGateway.class)).detachAppender(logs);
        provider.close();
    }

    private String loggedMessages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (all, next) -> all + next + "\n");
    }

    @Test
    void theModelPicksTheToolAndTheResultIsHandedBackBeforeTheSummary() {
        provider.thenRequestTool("read_account_profile", "{\"accountId\":\"" + OWNED_ACCOUNT + "\"}");
        provider.thenReplyWith("定位：Java 面试；栏目：并发；每周 2 条");
        provider.thenReplyWith("每周产出两条 Java 面试快问快答");

        var state = workflow.summarizeAccount(OWNER, OWNED_ACCOUNT);

        assertThat(state.accountFacts()).contains("定位：Java 面试；栏目：并发；每周 2 条");
        assertThat(state.summary()).contains("每周产出两条 Java 面试快问快答");
        assertThat(provider.calls()).isEqualTo(3);
        assertThat(provider.requestBodies().get(1))
                .contains("read_account_profile").contains("weeklyTarget")
                .containsPattern("\"role\"\\s*:\\s*\"tool\"");
        assertThat(loggedMessages()).containsPattern("tool read_account_profile finished: status=OK durationMs=\\d+");
    }

    @Test
    void anEndlessToolRequestLoopIsStoppedByTheRoundLimit() {
        provider.alwaysRequestTool("read_account_profile", "{\"accountId\":\"" + OWNED_ACCOUNT + "\"}");

        assertThatThrownBy(() -> workflow.summarizeAccount(OWNER, OWNED_ACCOUNT))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("AGENT_TOOL_LIMIT");
        assertThat(provider.calls()).isEqualTo(4);
    }

    @Test
    void anAccountOfAnotherUserIsNotReadableThroughTheTool() {
        assertThatThrownBy(() -> new AccountProfileTool(accounts, json, OWNER)
                .execute("{\"accountId\":\"" + OTHER_ACCOUNT + "\"}"))
                .isInstanceOf(LocalTool.ToolRejection.class).hasMessage("ACCOUNT_NOT_FOUND");
        assertThat(accounts.find(OTHER_USER, OTHER_ACCOUNT)).isPresent();
    }

    @Test
    void aToolArgumentPointingAtAnotherUserReturnsNoProfileData() {
        provider.thenRequestTool("read_account_profile", "{\"accountId\":\"" + OTHER_ACCOUNT + "\"}");
        provider.thenReplyWith("该账号不可用");
        provider.thenReplyWith("暂时无法给出建议");

        var state = workflow.summarizeAccount(OWNER, OWNED_ACCOUNT);

        assertThat(state.summary()).contains("暂时无法给出建议");
        assertThat(provider.requestBodies().get(1)).contains("ACCOUNT_NOT_FOUND").doesNotContain("weeklyTarget");
        assertThat(loggedMessages())
                .containsPattern("tool read_account_profile rejected: status=ACCOUNT_NOT_FOUND durationMs=\\d+");
    }

    @Test
    void unknownToolsAndUnusableArgumentsAreReportedInsteadOfExecuted() {
        provider.thenRequestTool("delete_everything", "{}");
        provider.thenRequestTool("read_account_profile", "不是 JSON");
        provider.thenReplyWith("无法读取账号配置");
        provider.thenReplyWith("需要先修正账号配置");

        var state = workflow.summarizeAccount(OWNER, OWNED_ACCOUNT);

        assertThat(state.summary()).contains("需要先修正账号配置");
        assertThat(provider.requestBodies().get(1)).contains("UNKNOWN_TOOL");
        assertThat(provider.requestBodies().get(2)).contains("TOOL_ARGUMENTS_INVALID");
    }
}
