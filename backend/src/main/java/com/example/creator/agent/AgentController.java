package com.example.creator.agent;

import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for a signed-in creator. Ownership always comes from CurrentUser. */
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final CurrentUser currentUser;
    private final AccountProfiles accounts;
    private final ContentWorkflow workflow;
    private final McpSearchGateway mcpSearch;

    AgentController(CurrentUser currentUser, AccountProfiles accounts, ContentWorkflow workflow,
                    McpSearchGateway mcpSearch) {
        this.currentUser = currentUser;
        this.accounts = accounts;
        this.workflow = workflow;
        this.mcpSearch = mcpSearch;
    }

    @GetMapping("/accounts")
    public List<AccountView> accounts() {
        return accounts.ownedBy(currentUser.id()).stream()
                .map(profile -> new AccountView(profile.id(), profile.name(), profile.positioning(),
                        profile.columns(), profile.weeklyTarget()))
                .toList();
    }

    @GetMapping("/mcp/tools")
    public ResponseEntity<?> mcpTools() {
        try {
            return ResponseEntity.ok(mcpSearch.discover());
        } catch (McpSearchGateway.McpFailure failure) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage());
        }
    }

    @PostMapping("/account-summaries")
    public ResponseEntity<?> summarize(@RequestBody(required = false) SummaryRequest request) {
        if (request == null || request.accountId() == null || request.accountId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT_ID");
        }
        long userId = currentUser.id();
        String accountId = request.accountId().strip();
        if (accounts.find(userId, accountId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
        }
        try {
            var state = workflow.summarizeAccount(userId, accountId);
            return ResponseEntity.ok(new SummaryView(accountId, state.summary()
                    .orElseThrow(() -> new ModelGateway.ModelFailure("AGENT_NO_RESULT"))));
        } catch (ModelGateway.ModelFailure failure) {
            return error(statusFor(failure.getMessage()), failure.getMessage());
        }
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case "MODEL_NOT_CONFIGURED", "MODEL_UPSTREAM_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "MODEL_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "MODEL_INVALID_OUTPUT" -> HttpStatus.BAD_GATEWAY;
            case "ACCOUNT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static ResponseEntity<ErrorView> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorView(code));
    }

    public record SummaryRequest(String accountId) { }
    public record AccountView(String id, String name, String positioning, List<String> columns, int weeklyTarget) { }
    public record SummaryView(String accountId, String summary) { }
    public record ErrorView(String code) { }
}
