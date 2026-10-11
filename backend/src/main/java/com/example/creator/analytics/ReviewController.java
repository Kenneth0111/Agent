package com.example.creator.analytics;

import com.example.creator.auth.CurrentUser;
import com.example.creator.material.MaterialSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics/review-evidence")
public class ReviewController {
    private final CurrentUser currentUser;
    private final ReviewService reviews;

    ReviewController(CurrentUser currentUser, ReviewService reviews) {
        this.currentUser = currentUser;
        this.reviews = reviews;
    }

    @GetMapping
    public ResponseEntity<?> evidence(@RequestParam(required = false) String accountId,
                                       @RequestParam(required = false) String q) {
        try {
            return ResponseEntity.ok(reviews.evidence(currentUser.id(), accountId, q));
        } catch (MaterialSearchService.SearchFailure invalid) {
            return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(invalid.getMessage()) ? 404 : 400)
                    .body(new ErrorView(invalid.getMessage()));
        }
    }
    public record ErrorView(String code) { }
}
