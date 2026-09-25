package com.example.creator.content;

import com.example.creator.auth.CurrentUser;
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

    GenerationController(CurrentUser currentUser, GenerationService generation, ContentService content) {
        this.currentUser = currentUser;
        this.generation = generation;
        this.content = content;
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

    public record ErrorView(String code) { }
}
