package com.example.creator.content;

import com.example.creator.agent.AccountProfile;
import com.example.creator.agent.AccountProfiles;
import com.example.creator.agent.ModelGateway;
import com.example.creator.content.ContentService.SavedTopic;
import com.example.creator.content.ContentValidator.ContentInvalid;
import com.example.creator.material.MaterialSearchService;
import com.example.creator.material.MaterialService;
import com.example.creator.material.MaterialService.MaterialDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** The generation path has explicit, bounded stages; a script run never creates another topic. */
@Component
class GenerationGraph {
    private final AccountProfiles accounts;
    private final MaterialService materials;
    private final MaterialSearchService search;
    private final ModelGateway model;
    private final ContentValidator validator;
    private final ContentService content;
    private final CompiledGraph<State> graph;

    GenerationGraph(AccountProfiles accounts, MaterialService materials, MaterialSearchService search,
                    ModelGateway model, ContentValidator validator, ContentService content) throws GraphStateException {
        this.accounts = accounts;
        this.materials = materials;
        this.search = search;
        this.model = model;
        this.validator = validator;
        this.content = content;
        this.graph = new StateGraph<>(State::new)
                .addNode("readAccount", node_async(this::readAccount))
                .addNode("retrieveEvidence", node_async(this::retrieveEvidence))
                .addNode("generateDraft", node_async(this::generateDraft))
                .addNode("validateDraft", node_async(this::validateDraft))
                .addNode("saveDraft", node_async(this::saveDraft))
                .addEdge(StateGraph.START, "readAccount")
                .addEdge("readAccount", "retrieveEvidence")
                .addEdge("retrieveEvidence", "generateDraft")
                .addEdge("generateDraft", "validateDraft")
                .addEdge("validateDraft", "saveDraft")
                .addEdge("saveDraft", StateGraph.END)
                .compile(CompileConfig.builder().recursionLimit(8).build());
    }

    Result run(long ownerId, GenerationService.Request request) {
        try {
            var state = graph.invoke(Map.of("ownerId", ownerId, "request", request))
                    .orElseThrow(() -> new StageFailure("saveDraft", "AGENT_NO_RESULT", 0));
            return new Result(state.resultId(), state.attempts());
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof StageFailure stage) throw stage;
            }
            throw failure;
        }
    }

    private Map<String, Object> readAccount(State state) {
        try {
            return Map.of("account", accounts.find(state.ownerId(), state.request().accountId())
                    .orElseThrow(() -> new ContentInvalid("ACCOUNT_NOT_FOUND")));
        } catch (ContentInvalid failure) {
            throw new StageFailure("readAccount", failure.getMessage(), 0);
        }
    }

    private Map<String, Object> retrieveEvidence(State state) {
        try {
            if ("TOPICS".equals(state.request().mode()))
                return Map.of("evidence", evidence(state.ownerId(), state.account().id(),
                        state.request().materialIds(), state.request().instruction()));
            var topic = content.findTopic(state.ownerId(), state.request().topicId())
                    .filter(found -> found.accountId().equals(state.account().id()))
                    .orElseThrow(() -> new ContentInvalid("TOPIC_NOT_FOUND"));
            var evidence = materialIds(state.ownerId(), state.account().id(), topic.topic().sourceIds());
            if (evidence.isEmpty()) throw new ContentInvalid("INSUFFICIENT_MATERIAL");
            return Map.of("savedTopic", topic, "evidence", evidence);
        } catch (ContentInvalid | MaterialSearchService.SearchFailure failure) {
            throw new StageFailure("retrieveEvidence", failure.getMessage(), 0);
        }
    }

    private Map<String, Object> generateDraft(State state) {
        var prompt = "TOPICS".equals(state.request().mode())
                ? GenerationPrompts.topic(state.account(), state.request().column(),
                        state.request().instruction().strip(), state.evidence())
                : GenerationPrompts.script(state.account(), state.savedTopic().topic(),
                        state.request().instruction().strip(), state.evidence());
        try {
            return Map.of("prompt", prompt, "raw", model.replyJson(prompt));
        } catch (ModelGateway.ModelFailure failure) {
            throw new StageFailure("generateDraft", failure.getMessage(), 1);
        }
    }

    private Map<String, Object> validateDraft(State state) {
        var allowed = ids(state.evidence());
        int attempts = 1;
        try {
            if ("TOPICS".equals(state.request().mode())) {
                ContentValidator.Topic topic;
                try { topic = validator.topic(state.raw(), allowed); }
                catch (ContentInvalid invalid) {
                    attempts = 2;
                    topic = validator.topic(model.replyJson(correction(state.prompt(), state.raw(), invalid.getMessage())), allowed);
                }
                if (!state.request().column().equals(topic.column())) throw new ContentInvalid("COLUMN_MISMATCH");
                return Map.of("validatedTopic", topic, "attempts", attempts);
            }
            ContentValidator.Script script;
            try { script = validator.script(state.raw(), allowed); }
            catch (ContentInvalid invalid) {
                attempts = 2;
                script = validator.script(model.replyJson(correction(state.prompt(), state.raw(), invalid.getMessage())), allowed);
            }
            if (script.sourceIds().isEmpty()) throw new ContentInvalid("INSUFFICIENT_MATERIAL");
            return Map.of("validatedScript", script, "attempts", attempts);
        } catch (ContentInvalid | ModelGateway.ModelFailure failure) {
            throw new StageFailure("validateDraft", failure.getMessage(), attempts);
        }
    }

    private Map<String, Object> saveDraft(State state) {
        try {
            var resultId = "TOPICS".equals(state.request().mode())
                    ? content.saveTopic(state.ownerId(), state.account().id(), state.validatedTopic(), ids(state.evidence()))
                    : content.saveScript(state.ownerId(), state.savedTopic().id(), state.validatedScript(), ids(state.evidence()));
            return Map.of("resultId", resultId);
        } catch (ContentInvalid failure) {
            throw new StageFailure("saveDraft", failure.getMessage(), state.attempts());
        }
    }

    private List<MaterialDetail> evidence(long ownerId, String accountId, List<String> sourceIds, String instruction) {
        if (sourceIds != null && !sourceIds.isEmpty()) return materialIds(ownerId, accountId, sourceIds);
        var query = instruction.strip();
        if (query.length() > 100) return List.of();
        return materialIds(ownerId, accountId, search.search(ownerId, accountId, query).results().stream()
                .limit(3).map(MaterialSearchService.SearchResult::materialId).toList());
    }

    private List<MaterialDetail> materialIds(long ownerId, String accountId, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.size() > 3 || sourceIds.stream().anyMatch(id -> id == null || id.isBlank())
                || sourceIds.stream().distinct().count() != sourceIds.size())
            throw new ContentInvalid("INVALID_MATERIAL_IDS");
        var result = new ArrayList<MaterialDetail>();
        for (var id : sourceIds) {
            var material = materials.find(ownerId, id).orElseThrow(() -> new ContentInvalid("MATERIAL_NOT_FOUND"));
            if (!material.accountIds().isEmpty() && !material.accountIds().contains(accountId))
                throw new ContentInvalid("MATERIAL_NOT_FOUND");
            result.add(material);
        }
        return result;
    }

    private Set<String> ids(List<MaterialDetail> evidence) {
        return evidence.stream().map(MaterialDetail::id).collect(Collectors.toUnmodifiableSet());
    }

    private String correction(String prompt, String raw, String code) {
        return prompt + "\n上次 JSON 未通过校验（" + code + "）。请仅返回修正后的完整 JSON。\n上次结果：\n"
                + raw.substring(0, Math.min(raw.length(), 4000));
    }

    record Result(String resultId, int attempts) { }
    static final class StageFailure extends RuntimeException {
        private final String node;
        private final int attempts;
        StageFailure(String node, String code, int attempts) {
            super(code, null, false, false);
            this.node = node;
            this.attempts = attempts;
        }
        String node() { return node; }
        int attempts() { return attempts; }
    }

    static final class State extends AgentState {
        State(Map<String, Object> data) { super(data); }
        long ownerId() { return this.<Number>value("ownerId").orElseThrow().longValue(); }
        GenerationService.Request request() { return this.<GenerationService.Request>value("request").orElseThrow(); }
        AccountProfile account() { return this.<AccountProfile>value("account").orElseThrow(); }
        List<MaterialDetail> evidence() { return this.<List<MaterialDetail>>value("evidence").orElseThrow(); }
        SavedTopic savedTopic() { return this.<SavedTopic>value("savedTopic").orElseThrow(); }
        String prompt() { return this.<String>value("prompt").orElseThrow(); }
        String raw() { return this.<String>value("raw").orElseThrow(); }
        ContentValidator.Topic validatedTopic() { return this.<ContentValidator.Topic>value("validatedTopic").orElseThrow(); }
        ContentValidator.Script validatedScript() { return this.<ContentValidator.Script>value("validatedScript").orElseThrow(); }
        int attempts() { return this.<Number>value("attempts").orElseThrow().intValue(); }
        String resultId() { return this.<String>value("resultId").orElseThrow(); }
    }
}
