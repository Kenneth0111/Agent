package com.example.creator.analytics;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OperationReviewService {
    private final ReviewStore store;
    private final OperationReviewGraph graph;

    OperationReviewService(ReviewStore store, OperationReviewGraph graph) {
        this.store = store;
        this.graph = graph;
    }

    public OperationReview.Report generate(long ownerId, OperationReview.Request request) {
        var input = validate(request);
        var reserved = store.reserve(ownerId, input);
        if (!reserved.created()) return reserved.report();
        try { return graph.run(ownerId, reserved.report().id(), input); }
        catch (ReviewFailure failure) {
            boolean unknown = List.of("MODEL_TIMEOUT", "MODEL_UPSTREAM_FAILED", "REVIEW_INTERNAL_FAILED")
                    .contains(failure.getMessage());
            return store.finish(ownerId, reserved.report().id(), unknown ? "NEEDS_REVIEW" : "FAILED", null, failure.getMessage());
        }
    }
    public Optional<OperationReview.Report> find(long ownerId, String id) { return store.find(ownerId, id); }
    public List<OperationReview.Report> list(long ownerId, String accountId) { return store.list(ownerId, accountId); }

    private OperationReview.Request validate(OperationReview.Request request) {
        try {
            if (request == null || request.accountId() == null || request.requestId() == null
                    || request.topic() == null || request.topic().isBlank()
                    || request.topic().strip().length() > 100 || request.from() == null || request.to() == null
                    || !request.from().isBefore(request.to()) || Duration.between(request.from(), request.to()).compareTo(Duration.ofDays(31)) > 0
                    || request.from().getNano() % 1000 != 0 || request.to().getNano() % 1000 != 0)
                throw new ReviewFailure("INVALID_REVIEW_REQUEST");
            for (var time : List.of(request.from(), request.to())) {
                int year = LocalDateTime.ofInstant(time, ZoneOffset.UTC).getYear();
                if (year < 1000 || year > 9999) throw new ReviewFailure("INVALID_REVIEW_REQUEST");
            }
            var account = UUID.fromString(request.accountId().strip()).toString();
            var requestId = UUID.fromString(request.requestId().strip()).toString();
            if (!account.equalsIgnoreCase(request.accountId().strip()) || !requestId.equalsIgnoreCase(request.requestId().strip()))
                throw new ReviewFailure("INVALID_REVIEW_REQUEST");
            return new OperationReview.Request(account, request.topic().strip(), request.from(), request.to(), requestId);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new ReviewFailure("INVALID_REVIEW_REQUEST");
        }
    }
    public static final class ReviewFailure extends RuntimeException {
        public ReviewFailure(String code) { super(code, null, false, false); }
    }
}
