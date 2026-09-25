package com.example.creator.content;

import com.example.creator.auth.CurrentUser;
import com.example.creator.agent.ModelGateway;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/generations")
public class GenerationController {
    private final CurrentUser currentUser;
    private final GenerationService generation;
    private final ContentService content;
    private final ConversationService conversation;

    GenerationController(CurrentUser currentUser, GenerationService generation, ContentService content,
                         ConversationService conversation) {
        this.currentUser = currentUser;
        this.generation = generation;
        this.content = content;
        this.conversation = conversation;
    }

    @PostMapping
    public ResponseEntity<?> generate(@RequestBody(required = false) GenerationService.Request request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(generation.generate(currentUser.id(), request));
        } catch (ContentValidator.ContentInvalid invalid) {
            return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(invalid.getMessage()) ? 404 : 400)
                    .body(new ErrorView(invalid.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContentService.GenerationRun> run(@PathVariable String id) {
        return ResponseEntity.of(content.findRun(currentUser.id(), id));
    }

    @GetMapping("/topics/{id}")
    public ResponseEntity<ContentService.SavedTopic> topic(@PathVariable String id) {
        return ResponseEntity.of(content.findTopic(currentUser.id(), id));
    }

    @GetMapping("/topics")
    public ResponseEntity<?> topics(@RequestParam String accountId) {
        try {
            return ResponseEntity.ok(content.topics(currentUser.id(), accountId));
        } catch (ContentValidator.ContentInvalid invalid) {
            return ResponseEntity.status(404).body(new ErrorView(invalid.getMessage()));
        }
    }

    @GetMapping("/scripts/{id}")
    public ResponseEntity<ContentService.SavedScript> script(@PathVariable String id) {
        return ResponseEntity.of(content.findScript(currentUser.id(), id));
    }

    @GetMapping("/scripts")
    public ResponseEntity<?> scripts(@RequestParam String topicId) {
        try {
            return ResponseEntity.ok(content.scripts(currentUser.id(), topicId));
        } catch (ContentValidator.ContentInvalid invalid) {
            return ResponseEntity.status(404).body(new ErrorView(invalid.getMessage()));
        }
    }

    @GetMapping("/scripts/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable String id) {
        try {
            return ResponseEntity.ok(content.versions(currentUser.id(), id));
        } catch (ContentValidator.ContentInvalid invalid) {
            return ResponseEntity.status(404).body(new ErrorView(invalid.getMessage()));
        }
    }

    @PostMapping("/scripts/{id}/revise")
    public ResponseEntity<?> revise(@PathVariable String id,
                                    @RequestBody(required = false) ConversationService.Revision request) {
        try {
            return ResponseEntity.ok(conversation.revise(currentUser.id(), id, request));
        } catch (ContentService.VersionConflict conflict) {
            return ResponseEntity.status(409).body(new ErrorView("VERSION_CONFLICT"));
        } catch (ContentValidator.ContentInvalid invalid) {
            var code = invalid.getMessage();
            return ResponseEntity.status(List.of("SCRIPT_NOT_FOUND", "TOPIC_NOT_FOUND", "CONVERSATION_NOT_FOUND",
                    "MATERIAL_NOT_FOUND").contains(code) ? 404 : 400).body(new ErrorView(code));
        } catch (ModelGateway.ModelFailure failure) {
            return ResponseEntity.status("MODEL_TIMEOUT".equals(failure.getMessage()) ? 504 : 503)
                    .body(new ErrorView(failure.getMessage()));
        }
    }

    public record ErrorView(String code) { }
}
