package com.example.creator.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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

    @Test
    void executesOnlyTheAllowlistedTavilySearchAndParsesSources() {
        var client = mock(McpClient.class);
        var result = mock(ToolExecutionResult.class);
        when(client.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("tavily_search").build(),
                ToolSpecification.builder().name("delete_records").build()));
        when(result.resultText()).thenReturn("""
                Request ID: test-request
                Detailed Results:

                Title: Java docs
                ID: source-1
                URL: https://example.test/java
                Content: Java reference text.
                """);
        when(client.executeTool(any())).thenReturn(result);
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of(),
                Set.of("tavily_search"), Duration.ofSeconds(1), (url, headers, timeout) -> client);

        var response = gateway.search("Java");

        assertThat(response.status()).isEqualTo("MATCHED");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).sourceUrl()).isEqualTo("https://example.test/java");
        assertThat(response.results().get(0).kind()).isEqualTo("WEB");
        verify(client).executeTool(org.mockito.ArgumentMatchers.argThat(call ->
                call.name().equals("tavily_search") && call.arguments().contains("\"query\":\"Java\"")));
    }

    @Test
    void parsesLiveTavilyJsonResultsAndRejectsNonWebUrls() {
        var client = mock(McpClient.class);
        var result = mock(ToolExecutionResult.class);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("tavily_search").build()));
        when(result.resultText()).thenReturn("""
                {"query":"Java","results":[
                  {"title":"Official Java docs","url":"https://docs.example.test/java","content":"Version reference"},
                  {"title":"Local file","url":"file:///secret","content":"not a web source"}
                ]}
                """);
        when(client.executeTool(any())).thenReturn(result);
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of(),
                Set.of("tavily_search"), Duration.ofSeconds(1), (url, headers, timeout) -> client);

        var response = gateway.search("Java");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).sourceUrl()).isEqualTo("https://docs.example.test/java");
        assertThat(response.results().get(0).snippet()).isEqualTo("Version reference");
    }

    @Test
    void providerErrorJsonDoesNotReachTheModelAsSearchEvidence() {
        var client = mock(McpClient.class);
        var result = mock(ToolExecutionResult.class);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("tavily_search").build()));
        when(result.resultText()).thenReturn("{\"error\":\"temporary provider failure\"}");
        when(client.executeTool(any())).thenReturn(result);
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of(),
                Set.of("tavily_search"), Duration.ofSeconds(1), (url, headers, timeout) -> client);

        assertThatThrownBy(() -> gateway.search("Java")).isInstanceOf(McpSearchGateway.McpFailure.class)
                .hasMessage("MCP_UNAVAILABLE");
    }

    @Test
    void refusesSearchWhenTheProviderDoesNotAdvertiseTheAllowedTool() {
        var client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("delete_records").build()));
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of(),
                Set.of("tavily_search"), Duration.ofSeconds(1), (url, headers, timeout) -> client);
        assertThatThrownBy(() -> gateway.search("Java")).isInstanceOf(McpSearchGateway.McpFailure.class)
                .hasMessage("MCP_SEARCH_TOOL_UNAVAILABLE");
    }

    @Test
    void streamableHttpClientReallyCallsAnMcpSearchTool() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        var calledQuery = new AtomicReference<String>();
        server.createContext("/mcp", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var request = json.readTree(body);
            var method = request.path("method").asText();
            if (method.startsWith("notifications/")) {
                exchange.sendResponseHeaders(202, -1);
            } else {
                String result = switch (method) {
                    case "initialize" -> """
                            {"protocolVersion":"2025-03-26","capabilities":{"tools":{}},
                             "serverInfo":{"name":"local-search-fixture","version":"1.0"}}
                            """;
                    case "tools/list" -> """
                            {"tools":[{"name":"tavily_search","description":"Search",
                            "inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}]}
                            """;
                    case "tools/call" -> {
                        calledQuery.set(request.path("params").path("arguments").path("query").asText());
                        yield """
                                {"content":[{"type":"text","text":"Detailed Results:\\n\\nTitle: Fixture source\\nURL: https://example.test/source\\nContent: Fixture evidence."}]}
                                """;
                    }
                    default -> throw new IllegalStateException("Unexpected MCP method: " + method);
                };
                var response = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.path("id"),
                        "result", json.readTree(result)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        try {
            var gateway = new McpSearchGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    Map.of(), Set.of("tavily_search"), Duration.ofSeconds(5));
            var response = gateway.search("Java interview");
            assertThat(calledQuery.get()).isEqualTo("Java interview");
            assertThat(response.results()).extracting(source -> source.sourceUrl())
                    .containsExactly("https://example.test/source");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closingAClientCannotDiscardAnAlreadyCompletedSearch() throws Exception {
        var client = mock(McpClient.class);
        var result = mock(ToolExecutionResult.class);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("tavily_search").build()));
        when(result.resultText()).thenReturn("Detailed Results:\n\nTitle: Source\nURL: https://example.test/ok\nContent: Evidence.");
        when(client.executeTool(any())).thenReturn(result);
        doThrow(new IllegalStateException("close failed")).when(client).close();
        var gateway = new McpSearchGateway("https://mcp.example.test/mcp", Map.of(),
                Set.of("tavily_search"), Duration.ofSeconds(1), (url, headers, timeout) -> client);
        assertThat(gateway.search("Java").results()).hasSize(1);
    }
}
