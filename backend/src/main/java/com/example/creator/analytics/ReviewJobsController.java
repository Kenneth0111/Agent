package com.example.creator.analytics;

import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/review-jobs/accounts/{accountId}")
public class ReviewJobsController {
    private final CurrentUser currentUser;
    private final ReviewJobStore jobs;
    private final ReviewJobScanner scanner;
    ReviewJobsController(CurrentUser currentUser, ReviewJobStore jobs, ReviewJobScanner scanner) {
        this.currentUser = currentUser; this.jobs = jobs; this.scanner = scanner;
    }
    @GetMapping
    public View find(@PathVariable String accountId) { return view(currentUser.id(), accountId); }
    @PutMapping
    public View save(@PathVariable String accountId, @RequestBody(required = false) ReviewJobStore.Input input) {
        long ownerId = currentUser.id();
        jobs.save(ownerId, accountId, input);
        return view(ownerId, accountId);
    }
    private View view(long ownerId, String accountId) {
        var job = jobs.findByAccount(ownerId, accountId).orElse(null);
        return new View(scanner.enabled(), job, job == null ? List.of() : jobs.triggers(ownerId, job.id()));
    }
    @ExceptionHandler(OperationReviewService.ReviewFailure.class)
    public ResponseEntity<ErrorView> failure(OperationReviewService.ReviewFailure invalid) {
        String code = invalid.getMessage();
        return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(code) || "JOB_NOT_FOUND".equals(code) ? 404 : 400).body(new ErrorView(code));
    }
    public record View(boolean schedulerEnabled, ReviewJobStore.Job job, List<ReviewJobStore.Trigger> triggers) { }
    public record ErrorView(String code) { }
}
