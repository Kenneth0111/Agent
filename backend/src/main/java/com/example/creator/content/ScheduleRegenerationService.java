package com.example.creator.content;

import java.util.List;
import org.springframework.stereotype.Service;

/** Regenerates one selected slot; the other weekly slots stay unchanged. */
@Service
public class ScheduleRegenerationService {
    private final ScheduleService schedules;
    private final ContentService content;
    private final GenerationGraph graph;

    ScheduleRegenerationService(ScheduleService schedules, ContentService content, GenerationGraph graph) {
        this.schedules = schedules;
        this.content = content;
        this.graph = graph;
    }

    public ScheduleService.Item regenerate(long ownerId, String itemId, Input input) {
        if (input == null || input.expectedVersion() < 1 || input.instruction() == null
                || input.instruction().isBlank() || input.instruction().strip().length() > 500
                || input.materialIds() == null || input.materialIds().isEmpty()
                || input.materialIds().size() > 3 || input.materialIds().stream().anyMatch(id -> id == null || id.isBlank()))
            throw new ContentValidator.ContentInvalid("INVALID_REGENERATION_REQUEST");
        var slot = schedules.slot(ownerId, itemId)
                .orElseThrow(() -> new ContentValidator.ContentInvalid("PLAN_ITEM_NOT_FOUND"));
        if (slot.item().version() != input.expectedVersion()) throw new ContentService.VersionConflict();
        if (slot.item().scriptId() != null && "CONFIRMED".equals(content.findScript(ownerId,
                slot.item().scriptId()).orElseThrow(
                () -> new ContentValidator.ContentInvalid("SCRIPT_NOT_FOUND")).status()))
            throw new ContentValidator.ContentInvalid("SCRIPT_CONFIRMED");
        var instruction = input.instruction().strip();
        var topic = graph.run(ownerId, new GenerationService.Request(slot.accountId(), "TOPICS",
                slot.item().column(), instruction, input.materialIds(), null));
        var script = graph.run(ownerId, new GenerationService.Request(slot.accountId(), "SCRIPT",
                null, instruction, null, topic.resultId()));
        return schedules.replaceDraft(ownerId, itemId, input.expectedVersion(), topic.resultId(), script.resultId());
    }

    public record Input(int expectedVersion, String instruction, List<String> materialIds) { }
}
