package com.example.creator.agent;

import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AccountProfiles accounts = mock(AccountProfiles.class);
    private final ContentWorkflow workflow = mock(ContentWorkflow.class);
    private final McpSearchGateway mcpSearch = mock(McpSearchGateway.class);
    private final ResearchWorkflow research = mock(ResearchWorkflow.class);
    private final AgentController controller = new AgentController(currentUser, accounts, workflow, mcpSearch, research);
    private final AccountProfile owned = new AccountProfile("owned", 7L, "Java 面试快问快答",
            "面向初中级 Java 开发者", List.of("并发"), 2);

    @BeforeEach
    void currentOwner() {
        when(currentUser.id()).thenReturn(7L);
        when(accounts.ownedBy(7L)).thenReturn(List.of(owned));
        when(accounts.find(7L, "owned")).thenReturn(java.util.Optional.of(owned));
    }

    @Test
    void accountListContainsOnlyTheSignedInOwnersProfiles() {
        var response = controller.accounts();
        assertThat(response).extracting(AgentController.AccountView::id).containsExactly("owned");
        verify(accounts).ownedBy(7L);
        verify(accounts, never()).ownedBy(8L);
    }

    @Test
    void foreignOrMissingAccountDoesNotReachTheWorkflow() {
        when(accounts.find(7L, "other-users-account")).thenReturn(java.util.Optional.empty());
        var response = controller.summarize(new AgentController.SummaryRequest("other-users-account"));
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(new AgentController.ErrorView("ACCOUNT_NOT_FOUND"));
        verify(workflow, never()).summarizeAccount(anyLong(), anyString());
    }

    @Test
    void missingAccountIdIsRejectedBeforeLookingUpOrRunningAnything() {
        var response = controller.summarize(new AgentController.SummaryRequest("  "));
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new AgentController.ErrorView("INVALID_ACCOUNT_ID"));
        verify(accounts, never()).find(anyLong(), anyString());
        verify(workflow, never()).summarizeAccount(anyLong(), anyString());
    }

    @Test
    void unavailableModelIsAnExplicitServiceUnavailableResult() {
        when(workflow.summarizeAccount(7L, "owned"))
                .thenThrow(new ModelGateway.ModelFailure("MODEL_NOT_CONFIGURED"));
        var response = controller.summarize(new AgentController.SummaryRequest(" owned "));
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new AgentController.ErrorView("MODEL_NOT_CONFIGURED"));
    }

    @Test
    void unconfiguredMcpIsAnExplicitServiceUnavailableResult() {
        when(mcpSearch.discover()).thenThrow(new McpSearchGateway.McpFailure("MCP_NOT_CONFIGURED"));
        var response = controller.mcpTools();
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new AgentController.ErrorView("MCP_NOT_CONFIGURED"));
    }

    @Test
    void researchRejectsInvalidInputAndForeignAccounts() {
        assertThat(controller.research(new AgentController.ResearchRequest("owned", " ")).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        assertThat(controller.research(new AgentController.ResearchRequest("foreign", "volatile")).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        verify(research, never()).research(7L, "owned", " ");
        verify(research, never()).research(7L, "foreign", "volatile");
    }
}
