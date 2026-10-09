package com.example.creator.notification;

import com.example.creator.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notification-settings")
public class NotificationController {
    private final CurrentUser currentUser;
    private final NotificationService notifications;

    NotificationController(CurrentUser currentUser, NotificationService notifications) {
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationService.SettingsView settings() {
        return notifications.settings(currentUser.id());
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody(required = false) NotificationService.SettingsInput input) {
        try {
            return ResponseEntity.ok(notifications.save(currentUser.id(), input));
        } catch (NotificationService.NotificationInvalid invalid) {
            return ResponseEntity.badRequest().body(new ErrorView(invalid.getMessage()));
        } catch (SecretCipher.KeyUnavailable missing) {
            return ResponseEntity.status(503).body(new ErrorView(missing.getMessage()));
        }
    }

    public record ErrorView(String code) { }
}
