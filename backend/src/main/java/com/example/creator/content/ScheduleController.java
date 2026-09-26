package com.example.creator.content;

import com.example.creator.auth.CurrentUser;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final CurrentUser currentUser;
    private final ScheduleService schedules;

    ScheduleController(CurrentUser currentUser, ScheduleService schedules) {
        this.currentUser = currentUser;
        this.schedules = schedules;
    }

    @PostMapping("/weeks")
    public ResponseEntity<?> create(@RequestBody(required = false) WeekInput input) {
        try {
            if (input == null) return ResponseEntity.badRequest().body(new ErrorView("INVALID_WEEK_START"));
            return ResponseEntity.status(201).body(schedules.create(currentUser.id(), input.accountId(), input.weekStart()));
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    @GetMapping("/weeks")
    public ResponseEntity<?> week(@RequestParam String accountId, @RequestParam LocalDate weekStart) {
        try {
            return schedules.findByAccountAndStart(currentUser.id(), accountId, weekStart)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    @PutMapping("/items/{id}/date")
    public ResponseEntity<?> move(@PathVariable String id, @RequestBody(required = false) DateInput input) {
        try {
            if (input == null) return ResponseEntity.badRequest().body(new ErrorView("INVALID_SCHEDULE_DATE"));
            return ResponseEntity.ok(schedules.move(currentUser.id(), id, input.expectedVersion(), input.scheduledDate()));
        } catch (ContentService.VersionConflict conflict) {
            return ResponseEntity.status(409).body(new ErrorView("VERSION_CONFLICT"));
        } catch (ContentValidator.ContentInvalid invalid) {
            return failure(invalid);
        }
    }

    private ResponseEntity<ErrorView> failure(ContentValidator.ContentInvalid invalid) {
        var code = invalid.getMessage();
        return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(code) || "PLAN_ITEM_NOT_FOUND".equals(code)
                ? 404 : 400).body(new ErrorView(code));
    }

    public record WeekInput(String accountId, LocalDate weekStart) { }
    public record DateInput(int expectedVersion, LocalDate scheduledDate) { }
    public record ErrorView(String code) { }
}
