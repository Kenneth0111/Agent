package com.example.creator.content;

import com.example.creator.agent.AccountProfiles;
import com.example.creator.agent.ModelGateway;
import com.example.creator.content.ContentService.GenerationRun;
import com.example.creator.content.ContentValidator.ContentInvalid;
import com.example.creator.material.MaterialSearchService;
import com.example.creator.material.MaterialService;
import com.example.creator.material.MaterialService.MaterialDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Synchronous first slice: each attempt is persisted and can be inspected after a failure. */
@Service
public class GenerationService {
    private final AccountProfiles accounts;
    private final MaterialService materials;
    private final MaterialSearchService search;
    private final ModelGateway model;
    private final ContentValidator validator;
    private final ContentService content;

    GenerationService(AccountProfiles accounts, MaterialService materials, MaterialSearchService search,
                      ModelGateway model, ContentValidator validator, ContentService content) {
        this.accounts = accounts;
        this.materials = materials;
        this.search = search;
        this.model = model;
        this.validator = validator;
        this.content = content;
    }

    public GenerationRun generate(long ownerId, Request request) {
        if (request == null || request.accountId() == null || request.mode() == null
                || request.instruction() == null || request.instruction().isBlank()
                || request.instruction().strip().length() > 500)
            throw new ContentInvalid("INVALID_GENERATION_REQUEST");
        var account = accounts.find(ownerId, request.accountId()).orElseThrow(() -> new ContentInvalid("ACCOUNT_NOT_FOUND"));
        if (!List.of("TOPICS", "SCRIPT").contains(request.mode())) throw new ContentInvalid("INVALID_MODE");
        if ("TOPICS".equals(request.mode()) && !List.of("Java 面试", "英语跟读").contains(request.column()))
            throw new ContentInvalid("INVALID_COLUMN");
        if ("SCRIPT".equals(request.mode()) && (request.topicId() == null || request.topicId().isBlank()))
            throw new ContentInvalid("TOPIC_NOT_FOUND");

        var run = content.startRun(ownerId, account.id(), request.mode());
        int attempts = 0;
        try {
            if ("TOPICS".equals(request.mode())) {
                var evidence = evidence(ownerId, account.id(), request.materialIds(), request.instruction());
                var allowed = ids(evidence);
                var prompt = GenerationPrompts.topic(account, request.column(), request.instruction().strip(), evidence);
                attempts = 1;
                var raw = model.replyJson(prompt);
                ContentValidator.Topic topic;
                try {
                    topic = validator.topic(raw, allowed);
                } catch (ContentInvalid invalid) {
                    attempts = 2;
                    topic = validator.topic(model.replyJson(correction(prompt, raw, invalid.getMessage())), allowed);
                }
                if (!request.column().equals(topic.column())) throw new ContentInvalid("COLUMN_MISMATCH");
                return content.finishRun(ownerId, run.id(), content.saveTopic(ownerId, account.id(), topic, allowed), attempts);
            }

            var saved = content.findTopic(ownerId, request.topicId())
                    .filter(topic -> topic.accountId().equals(account.id()))
                    .orElseThrow(() -> new ContentInvalid("TOPIC_NOT_FOUND"));
            var evidence = materialIds(ownerId, account.id(), saved.topic().sourceIds());
            if (evidence.isEmpty()) throw new ContentInvalid("INSUFFICIENT_MATERIAL");
            var allowed = ids(evidence);
            var prompt = GenerationPrompts.script(account, saved.topic(), request.instruction().strip(), evidence);
            attempts = 1;
            var raw = model.replyJson(prompt);
            ContentValidator.Script script;
            try {
                script = validator.script(raw, allowed);
            } catch (ContentInvalid invalid) {
                attempts = 2;
                script = validator.script(model.replyJson(correction(prompt, raw, invalid.getMessage())), allowed);
            }
            if (script.sourceIds().isEmpty()) throw new ContentInvalid("INSUFFICIENT_MATERIAL");
            return content.finishRun(ownerId, run.id(), content.saveScript(ownerId, saved.id(), script, allowed), attempts);
        } catch (ModelGateway.ModelFailure | ContentInvalid failure) {
            return content.failRun(ownerId, run.id(), failure.getMessage(), attempts);
        }
    }

    private List<MaterialDetail> evidence(long ownerId, String accountId, List<String> materialIds, String instruction) {
        if (materialIds != null && !materialIds.isEmpty()) return materialIds(ownerId, accountId, materialIds);
        var query = instruction.strip();
        if (query.length() > 100) return List.of();
        return materialIds(ownerId, accountId, search.search(ownerId, accountId, query).results().stream()
                .limit(3).map(MaterialSearchService.SearchResult::materialId).toList());
    }

    private List<MaterialDetail> materialIds(long ownerId, String accountId, List<String> materialIds) {
        if (materialIds == null || materialIds.size() > 3 || materialIds.stream().anyMatch(id -> id == null || id.isBlank())
                || materialIds.stream().distinct().count() != materialIds.size())
            throw new ContentInvalid("INVALID_MATERIAL_IDS");
        var result = new ArrayList<MaterialDetail>();
        for (var id : materialIds) {
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

    public record Request(String accountId, String mode, String column, String instruction,
                          List<String> materialIds, String topicId) { }
}
