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
        if (!List.of("TOPICS", "SCRIPT").contains(request.mode())) throw new ContentInvalid("INVALID_MODE");
        if ("TOPICS".equals(request.mode()) && !List.of("Java 面试", "英语跟读").contains(request.column()))
            throw new ContentInvalid("INVALID_COLUMN");
        if ("SCRIPT".equals(request.mode()) && (request.topicId() == null || request.topicId().isBlank()))
            throw new ContentInvalid("TOPIC_NOT_FOUND");

        var run = content.startRun(ownerId, request.accountId(), request.mode());
        try {
            var result = graph.run(ownerId, request);
            return content.finishRun(ownerId, run.id(), result.resultId(), result.attempts());
        } catch (GenerationGraph.StageFailure failure) {
            return content.failRun(ownerId, run.id(), failure.getMessage(), failure.node(), failure.attempts());
        }
    }

    public record Request(String accountId, String mode, String column, String instruction,
                          List<String> materialIds, String topicId) implements java.io.Serializable { }
}
