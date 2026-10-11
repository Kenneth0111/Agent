package com.example.creator.analytics;

import java.time.Instant;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReviewJobScanner {
    private static final Logger log = LoggerFactory.getLogger(ReviewJobScanner.class);
    private final ReviewJobStore jobs;
    private final OperationReviewService reviews;
    private final RedissonClient redisson;
    private final boolean enabled;
    ReviewJobScanner(ReviewJobStore jobs, OperationReviewService reviews, RedissonClient redisson,
                     @Value("${creator.reviews.scheduler-enabled:false}") boolean enabled) {
        this.jobs = jobs; this.reviews = reviews; this.redisson = redisson; this.enabled = enabled;
    }
    public boolean enabled() { return enabled; }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { reconcile(); }
    @Scheduled(fixedDelay = 60_000)
    public void scheduledScan() {
        reconcile();
        if (enabled) scanDue(Instant.now());
    }
    public void reconcile() {
        var lock = redisson.getLock("generation:paid");
        if (!lock.tryLock()) return;
        try { jobs.reconcile(); }
        finally { lock.unlock(); }
    }
    public void scanDue(Instant now) {
        var lock = redisson.getLock("generation:paid");
        if (!lock.tryLock()) return;
        try {
            jobs.reconcile();
            for (var target : jobs.enabled()) {
                try {
                    var execution = jobs.reserveDue(target.ownerId(), target.id(), now);
                    if (execution.isEmpty()) continue;
                    var run = execution.get();
                    try {
                        var report = reviews.generate(run.ownerId(), run.request());
                        jobs.finish(run.ownerId(), run.id(), report.status(), report.errorCode());
                    } catch (RuntimeException failure) {
                        jobs.finish(run.ownerId(), run.id(), "NEEDS_REVIEW", "REVIEW_TRIGGER_UNKNOWN");
                        log.warn("Scheduled review {} requires review: {}", run.id(), failure.getClass().getSimpleName());
                    }
                } catch (RuntimeException failure) {
                    log.warn("Review job {} could not execute: {}", target.id(), failure.getClass().getSimpleName());
                }
            }
        } finally { lock.unlock(); }
    }
}
