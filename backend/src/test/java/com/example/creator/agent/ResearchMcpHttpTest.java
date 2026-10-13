package com.example.creator.agent;

import com.example.creator.material.MaterialSearchService;
import com.example.creator.material.MaterialSearchService.SearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the real MCP transport and research graph without paid services or a database. */
class ResearchMcpHttpTest {
    @ParameterizedTest
    @CsvSource({"tool-error,MCP_UNAVAILABLE", "invalid-result,MCP_INVALID_RESULT", "http-error,MCP_UNAVAILABLE"})
    void failedMcpHttpSearchProducesNoAnswerOrModelCall(String failure, String expectedStatus) throws Exception {
        var json = new ObjectMapper();
        var search = mock(MaterialSearchService.class);
        var model = mock(ModelGateway.class);
        when(search.search(7L, "owned-account", "missing evidence"))
                .thenReturn(new SearchResponse("INSUFFICIENT_MATERIAL", List.of()));
        var calledQuery = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                var request = json.readTree(exchange.getRequestBody().readAllBytes());
                var method = request.path("method").asText();
                if (method.startsWith("notifications/")) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                if (method.equals("tools/call")) {
                    calledQuery.set(request.path("params").path("arguments").path("query").asText());
                    if (failure.equals("http-error")) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                }
                var result = switch (method) {
                    case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "failure-fixture", "version", "1.0"));
                    case "tools/list" -> Map.of("tools", List.of(Map.of("name", "tavily_search",
                            "inputSchema", Map.of("type", "object", "properties",
                                    Map.of("query", Map.of("type", "string"))))));
                    case "tools/call" -> Map.of("isError", failure.equals("tool-error"), "content",
                            List.of(Map.of("type", "text", "text", "not a source or a verified answer")));
                    default -> throw new IllegalStateException("Unexpected MCP method: " + method);
                };
                var response = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.path("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var gateway = new McpSearchGateway("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    Map.of(), Set.of("tavily_search"), Duration.ofSeconds(2));
            var workflow = new ResearchWorkflow(search, model, gateway, json);
            var response = workflow.research(7L, "owned-account", "missing evidence");

            assertThat(calledQuery.get()).isEqualTo("missing evidence");
            assertThat(response.status()).isEqualTo("INSUFFICIENT_MATERIAL");
            assertThat(response.webSearchStatus()).isEqualTo(expectedStatus);
            assertThat(response.answer()).isNull();
            assertThat(response.sources()).isEmpty();
            verifyNoInteractions(model);
        } finally {
            server.stop(0);
        }
    }
}
