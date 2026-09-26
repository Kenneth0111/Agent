package com.example.creator.account;

import com.example.creator.agent.AccountProfile;
import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final CurrentUser currentUser;
    private final AccountService accounts;

    AccountController(CurrentUser currentUser, AccountService accounts) {
        this.currentUser = currentUser;
        this.accounts = accounts;
    }

    @GetMapping
    public List<AccountProfile> list() {
        return accounts.ownedBy(currentUser.id());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountProfile> get(@PathVariable String id) {
        return ResponseEntity.of(accounts.find(currentUser.id(), id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) AccountService.AccountInput input) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(accounts.create(currentUser.id(), input));
        } catch (IllegalArgumentException invalid) {
            return invalid();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody(required = false) AccountService.AccountInput input) {
        try {
            return accounts.update(currentUser.id(), id, input)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException invalid) {
            return invalid();
        }
    }

    private ResponseEntity<ErrorView> invalid() {
        return ResponseEntity.badRequest().body(new ErrorView("INVALID_ACCOUNT"));
    }

    public record ErrorView(String code) { }
}
