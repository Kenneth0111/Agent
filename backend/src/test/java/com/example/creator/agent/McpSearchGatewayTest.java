package com.example.creator.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSearchGatewayTest {
    @Test
    void blankEndpointFailsBeforeAnyNetworkOrClientCreation() {
        var gateway = new McpSearchGateway("", Map.of(), Set.of("search"), Duration.ofSeconds(1),
                (url, headers, timeout) -> { throw new AssertionError("must not connect"); });
        assertThatThrownBy(gateway::discover).isInstanceOf(McpSearchGateway.McpFailure.class)
                .hasMessage("MCP_NOT_CONFIGURED");
    }

    @Test
    void onlyExplicitlyAllowedToolsAreReturned() {
        var client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("search").description("search the public web").build(),
                ToolSpecification.builder().name("delete_records").description("unsafe").build()));
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of("Authorization", "Bearer secret"),
                Set.of("search"), Duration.ofSeconds(1), (url, headers, timeout) -> client);
        assertThat(gateway.discover()).containsExactly(new McpSearchGateway.DiscoveredTool("search", "search the public web"));
    }

    @Test
    void unavailableProviderDoesNotExposeItsFailure() {
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of(), Set.of("search"),
                Duration.ofSeconds(1), (url, headers, timeout) -> { throw new IllegalStateException("provider detail"); });
        assertThatThrownBy(gateway::discover).isInstanceOf(McpSearchGateway.McpFailure.class)
                .hasMessage("MCP_UNAVAILABLE");
    }
}
