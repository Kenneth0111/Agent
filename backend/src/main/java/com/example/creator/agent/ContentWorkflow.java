package com.example.creator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Reads the caller's account configuration through a tool, then summarises it. */
@Component
public class ContentWorkflow {
    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_GRAPH_STEPS = 6;
    private static final String READ_INSTRUCTION = """
            ???????????? read_account_profile ???????????????????????????
            ???? ACCOUNT_NOT_FOUND ????????????????? ID?""";
    private static final String SUMMARY_INSTRUCTION = "?????? 40 ??????????????????";

    private final ModelGateway model;
    private final AccountProfiles accounts;
    private final ObjectMapper json;
    private final CompiledGraph<ContentState> graph;

    ContentWorkflow(ModelGateway model, AccountProfiles accounts, ObjectMapper json) throws GraphStateException {
        this.model = model;
        this.accounts = accounts;
        this.json = json;
        this.graph = new StateGraph<>(ContentState::new)
                .addNode("readAccount", node_async(this::readAccount))
                .addNode("summarize", node_async(this::summarize))
                .addEdge(StateGraph.START, "readAccount")
                .addEdge("readAccount", "summarize")
                .addEdge("summarize", StateGraph.END)
                .compile(CompileConfig.builder().recursionLimit(MAX_GRAPH_STEPS).build());
    }

    public ContentState summarizeAccount(long userId, String accountId) {
        try {
            return graph.invoke(Map.of("userId", userId, "accountId", accountId))
                    .orElseThrow(() -> new ModelGateway.ModelFailure("AGENT_NO_RESULT"));
        } catch (RuntimeException failure) {
            // The graph wraps node failures; callers need the original code to react to it.
            throw originalCause(failure);
        }
    }

    private static RuntimeException originalCause(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelGateway.ModelFailure modelFailure) return modelFailure;
        }
        return failure;
    }

    private Map<String, Object> readAccount(ContentState state) {
        var tool = new AccountProfileTool(accounts, json, state.userId());
        return Map.of("accountFacts", model.replyUsingTools("readAccount", READ_INSTRUCTION,
                "?? ID?" + state.accountId(), List.<LocalTool>of(tool), MAX_TOOL_ROUNDS));
    }

    private Map<String, Object> summarize(ContentState state) {
        var facts = state.accountFacts().orElseThrow(() -> new ModelGateway.ModelFailure("AGENT_NO_RESULT"));
        return Map.of("summary", model.reply(SUMMARY_INSTRUCTION + facts));
    }
}
