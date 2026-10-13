package com.example.creator.analytics;

import com.example.creator.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/reviews")
public class OperationReviewController {
    private final CurrentUser currentUser;
    private final OperationReviewService reviews;

    OperationReviewController(CurrentUser currentUser, OperationReviewService reviews) {
        this.currentUser = currentUser; this.reviews = reviews;
    }
    @PostMapping
    public ResponseEntity<OperationReview.Report> generate(@RequestBody(required = false) OperationReview.Request request) {
        return ResponseEntity.status(201).body(reviews.generate(currentUser.id(), request));
    }
    @GetMapping
    public ResponseEntity<?> list(@RequestParam String accountId) {
        return ResponseEntity.ok(reviews.list(currentUser.id(), accountId));
    }
    @GetMapping("/{id}")
    public ResponseEntity<OperationReview.Report> find(@PathVariable String id) {
        return ResponseEntity.of(reviews.find(currentUser.id(), id));
    }
    @ExceptionHandler(OperationReviewService.ReviewFailure.class)
    public ResponseEntity<ErrorView> invalid(OperationReviewService.ReviewFailure invalid) {
        var code = invalid.getMessage();
        return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(code) ? 404 : "REVIEW_REQUEST_CONFLICT".equals(code) ? 409 : 400)
                .body(new ErrorView(code));
    }
    public record ErrorView(String code) { }
}
