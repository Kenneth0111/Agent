package com.example.creator.content;

import com.example.creator.content.ContentService.GenerationRun;
import com.example.creator.content.ContentValidator.ContentInvalid;
import java.util.List;
import org.springframework.stereotype.Service;

/** Starts a durable run and delegates each content stage to the graph. */
@Service
public class GenerationService {
    private final ContentService content;
    private final GenerationGraph graph;

    GenerationService(ContentService content, GenerationGraph graph) {
        this.content = content;
        this.graph = graph;
    }

    public GenerationRun generate(long ownerId, Request request) {
        if (request == null || request.accountId() == null || request.mode() == null
                || request.instruction() == null || request.instruction().isBlank()
                || request.instruction().strip().length() > 500)
            throw new ContentInvalid("INVALID_GENERATION_REQUEST");
        if (!List.of("TOPICS", "SCRIPT", "WEEK_PLAN").contains(request.mode()))
            throw new ContentInvalid("INVALID_MODE");
        if ("TOPICS".equals(request.mode()) && !List.of("Java 面试", "英语跟读").contains(request.column()))
            throw new ContentInvalid("INVALID_COLUMN");
        if ("SCRIPT".equals(request.mode()) && (request.topicId() == null || request.topicId().isBlank()))
            throw new ContentInvalid("TOPIC_NOT_FOUND");
        if ("WEEK_PLAN".equals(request.mode())) return generateWeek(ownerId, request);

        var run = content.startRun(ownerId, request.accountId(), request.mode());
        try {
            var result = graph.run(ownerId, request);
            return content.finishRun(ownerId, run.id(), result.resultId(), result.attempts());
        } catch (GenerationGraph.StageFailure failure) {
            return content.failRun(ownerId, run.id(), failure.getMessage(), failure.node(), failure.attempts());
        }
    }

    private GenerationRun generateWeek(long ownerId, Request request) {
        var slots = request.slots();
        if (slots == null || slots.size() != 3
                || slots.stream().anyMatch(slot -> slot == null || slot.materialIds() == null
                        || slot.materialIds().isEmpty() || slot.materialIds().size() > 3
                        || (slot.instruction() != null && slot.instruction().length() > 500))
                || !"Java 面试".equals(slots.get(0).column())
                || !"Java 面试".equals(slots.get(1).column()) || !"英语跟读".equals(slots.get(2).column()))
            throw new ContentInvalid("INVALID_WEEK_PLAN");
        var run = content.startRun(ownerId, request.accountId(), "WEEK_PLAN");
        var items = new java.util.ArrayList<ContentService.WeekItem>();
        int attempts = 0;
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            var instruction = request.instruction().strip() + "；第 " + (index + 1) + " 条："
                    + (slot.instruction() == null ? "" : slot.instruction().strip());
            try {
                var topic = graph.run(ownerId, new Request(request.accountId(), "TOPICS", slot.column(),
                        instruction, slot.materialIds(), null));
                attempts += topic.attempts();
                var script = graph.run(ownerId, new Request(request.accountId(), "SCRIPT", null,
                        instruction, null, topic.resultId()));
                attempts += script.attempts();
                items.add(new ContentService.WeekItem(slot.column(), topic.resultId(), script.resultId()));
            } catch (GenerationGraph.StageFailure failure) {
                return content.failRun(ownerId, run.id(), failure.getMessage(),
                        "slot" + (index + 1) + "/" + failure.node(), attempts + failure.attempts());
            }
        }
        try {
            var id = content.saveWeekPlan(ownerId, request.accountId(), items);
            return content.finishRun(ownerId, run.id(), id, attempts);
        } catch (ContentInvalid failure) {
            return content.failRun(ownerId, run.id(), failure.getMessage(), "saveWeekPlan", attempts);
        }
    }

    public record Request(String accountId, String mode, String column, String instruction,
                          List<String> materialIds, String topicId, List<WeekSlot> slots) implements java.io.Serializable {
        public Request(String accountId, String mode, String column, String instruction,
                       List<String> materialIds, String topicId) {
            this(accountId, mode, column, instruction, materialIds, topicId, null);
        }
    }
    public record WeekSlot(String column, List<String> materialIds, String instruction) implements java.io.Serializable { }
}
