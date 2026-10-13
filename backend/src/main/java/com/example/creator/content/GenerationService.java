package com.example.creator.content;

import com.example.creator.content.ContentService.GenerationRun;
import com.example.creator.content.ContentService.WeekStage;
import com.example.creator.content.ContentValidator.ContentInvalid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            var tracked = new Request(request.accountId(), request.mode(), request.column(),
                    request.instruction(), request.materialIds(), request.topicId(), request.slots(), run.id());
            var result = graph.run(ownerId, tracked);
            return content.finishRun(ownerId, run.id(), result.resultId(), result.attempts());
        } catch (GenerationGraph.StageFailure failure) {
            return content.failRun(ownerId, run.id(), failure.getMessage(), failure.node(), failure.attempts());
        }
    }

    private GenerationRun generateWeek(long ownerId, Request request) {
        validateWeek(request);
        var reservation = content.reserveWeekRun(ownerId, request);
        if (!reservation.created()) return reservation.run();
        return continueWeek(ownerId, request, reservation.run(), List.of());
    }

    /** Only completed checkpoints are replayable; a started stage may contain an unknown paid call. */
    Optional<GenerationRun> resumeWeek(long ownerId, String runId, Request request) {
        validateWeek(request);
        var run = content.findRun(ownerId, runId).orElse(null);
        if (run == null || !"RUNNING".equals(run.status()) || !"WEEK_PLAN".equals(run.mode())
                || !request.accountId().equals(run.accountId())) return Optional.empty();
        var stages = content.weekStages(ownerId, runId);
        if (!safeToResume(stages)) return Optional.empty();
        return Optional.of(continueWeek(ownerId, request, run, stages));
    }

    private boolean safeToResume(List<WeekStage> stages) {
        if (stages.isEmpty() || stages.size() > 7) return false;
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            var name = index == 6 ? "saveWeekPlan" : "slot" + (index / 2 + 1) + "/"
                    + (index % 2 == 0 ? "TOPICS" : "SCRIPT");
            if (stage.index() != index || !name.equals(stage.name())
                    || !"COMPLETED".equals(stage.status()) || stage.outputId() == null
                    || stage.outputId().isBlank()) return false;
        }
        return true;
    }

    private void validateWeek(Request request) {
        var slots = request.slots();
        if (slots == null || slots.size() != 3
                || slots.stream().anyMatch(slot -> slot == null || slot.materialIds() == null
                        || slot.materialIds().isEmpty() || slot.materialIds().size() > 3
                        || (slot.instruction() != null && slot.instruction().length() > 500))
                || !"Java 面试".equals(slots.get(0).column())
                || !"Java 面试".equals(slots.get(1).column()) || !"英语跟读".equals(slots.get(2).column()))
            throw new ContentInvalid("INVALID_WEEK_PLAN");
    }

    private GenerationRun continueWeek(long ownerId, Request request, GenerationRun run,
                                       List<WeekStage> completed) {
        var slots = request.slots();
        var items = new ArrayList<ContentService.WeekItem>();
        int attempts = completed.stream().mapToInt(WeekStage::attempts).sum();
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            var instruction = request.instruction().strip() + "；第 " + (index + 1) + " 条："
                    + (slot.instruction() == null ? "" : slot.instruction().strip());
            try {
                var topicRequest = new Request(request.accountId(), "TOPICS", slot.column(),
                        instruction, slot.materialIds(), null, null, request.requestId());
                var topic = stage(ownerId, run.id(), index * 2, "slot" + (index + 1) + "/TOPICS",
                        topicRequest, completed);
                attempts += topic.attempts();
                var scriptRequest = new Request(request.accountId(), "SCRIPT", null,
                        instruction, null, topic.resultId(), null, request.requestId());
                var script = stage(ownerId, run.id(), index * 2 + 1, "slot" + (index + 1) + "/SCRIPT",
                        scriptRequest, completed);
                attempts += script.attempts();
                items.add(new ContentService.WeekItem(slot.column(), topic.resultId(), script.resultId()));
            } catch (GenerationGraph.StageFailure failure) {
                return content.failRun(ownerId, run.id(), failure.getMessage(),
                        "slot" + (index + 1) + "/" + failure.node(), attempts + failure.attempts());
            }
        }
        try {
            String id;
            if (completed.size() == 7) id = completed.get(6).outputId();
            else {
                content.startWeekStage(ownerId, run.id(), 6, "saveWeekPlan", items);
                id = content.saveWeekPlan(ownerId, request.accountId(), items);
                content.completeWeekStage(ownerId, run.id(), 6, id, 0);
            }
            return content.finishRun(ownerId, run.id(), id, attempts);
        } catch (ContentInvalid failure) {
            return content.failRun(ownerId, run.id(), failure.getMessage(), "saveWeekPlan", attempts);
        }
    }

    private GenerationGraph.Result stage(long ownerId, String runId, int index, String name, Request input,
                                         List<WeekStage> completed) {
        if (index < completed.size()) return new GenerationGraph.Result(completed.get(index).outputId(), 0);
        content.startWeekStage(ownerId, runId, index, name, input);
        var result = graph.run(ownerId, input);
        content.completeWeekStage(ownerId, runId, index, result.resultId(), result.attempts());
        return result;
    }

    public record Request(String accountId, String mode, String column, String instruction,
                          List<String> materialIds, String topicId, List<WeekSlot> slots,
                          String requestId) implements java.io.Serializable {
        public Request(String accountId, String mode, String column, String instruction,
                       List<String> materialIds, String topicId) {
            this(accountId, mode, column, instruction, materialIds, topicId, null, null);
        }
        public Request(String accountId, String mode, String column, String instruction,
                       List<String> materialIds, String topicId, List<WeekSlot> slots) {
            this(accountId, mode, column, instruction, materialIds, topicId, slots, null);
        }
    }
    public record WeekSlot(String column, List<String> materialIds, String instruction) implements java.io.Serializable { }
}
