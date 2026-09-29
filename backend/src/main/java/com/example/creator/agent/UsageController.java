package com.example.creator.agent;

import com.example.creator.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {
    private final CurrentUser currentUser;
    private final UsageLedger ledger;

    UsageController(CurrentUser currentUser, UsageLedger ledger) {
        this.currentUser = currentUser;
        this.ledger = ledger;
    }

    @GetMapping("/summary")
    public UsageLedger.Summary summary() {
        return ledger.summary(currentUser.id());
    }

    @GetMapping("/calls")
    public java.util.List<UsageLedger.Call> calls() {
        return ledger.recent(currentUser.id());
    }
}
