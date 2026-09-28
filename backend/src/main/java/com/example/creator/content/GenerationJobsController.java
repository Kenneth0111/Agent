package com.example.creator.content;

import com.example.creator.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/generation-jobs")
public class GenerationJobsController {
    private final CurrentUser currentUser;
    private final GenerationJobsService jobs;

    GenerationJobsController(CurrentUser currentUser, GenerationJobsService jobs) {
        this.currentUser = currentUser;
        this.jobs = jobs;
    }

    @PutMapping("/accounts/{accountId}")
    public ResponseEntity<?> save(@PathVariable String accountId,
                                  @RequestBody(required = false) GenerationJobsService.JobInput input) {
        try {
            return ResponseEntity.ok(jobs.save(currentUser.id(), accountId, input));
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<?> accountJob(@PathVariable String accountId) {
        try {
            return jobs.findByAccount(currentUser.id(), accountId)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<?> trigger(@PathVariable String id) {
        try {
            return ResponseEntity.ok(jobs.trigger(currentUser.id(), id,
                    GenerationJobsService.TriggerSource.MANUAL));
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    @GetMapping("/{id}/triggers")
    public ResponseEntity<?> triggers(@PathVariable String id) {
        try {
            return ResponseEntity.ok(jobs.triggers(currentUser.id(), id));
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    private ResponseEntity<ErrorView> failure(ContentValidator.ContentInvalid invalid) {
        var code = invalid.getMessage();
        return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(code) || "JOB_NOT_FOUND".equals(code)
                ? 404 : "JOB_DISABLED".equals(code) || "GENERATION_BUSY".equals(code) ? 409 : 400)
                .body(new ErrorView(code));
    }

    public record ErrorView(String code) { }
}
