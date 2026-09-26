package com.example.creator.agent;

import com.example.creator.material.MaterialSearchService.SearchResponse;
import com.example.creator.material.MaterialSearchService.SearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.time.Duration;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Discovers only explicitly allowed search tools; it does not expose user data to MCP servers. */
public class McpSearchGateway {
    private static final Logger log = LoggerFactory.getLogger(McpSearchGateway.class);
    private static final String SEARCH_TOOL = "tavily_search";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RESULT = Pattern.compile(
            "(?ms)^Title: ([^\\r\\n]*)\\r?\\n(?:ID: [^\\r\\n]*\\r?\\n)?URL: (https?://\\S+)\\r?\\nContent: (.*?)(?=\\r?\\n\\r?\\nTitle: |\\r?\\nRaw Content:|\\r?\\nFavicon:|\\r?\\nImages:|\\z)");

    private final String endpoint;
    private final Map<String, String> headers;
    private final Set<String> allowedTools;
    private final Duration timeout;
    private final ClientFactory clientFactory;

    public McpSearchGateway(String endpoint, Map<String, String> headers, Set<String> allowedTools,
                            Duration timeout) {
        this(endpoint, headers, allowedTools, timeout, McpSearchGateway::connect);
    }

    McpSearchGateway(String endpoint, Map<String, String> headers, Set<String> allowedTools,
                     Duration timeout, ClientFactory clientFactory) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.headers = Map.copyOf(headers);
        this.allowedTools = Set.copyOf(allowedTools);
        this.timeout = timeout;
        this.clientFactory = clientFactory;
    }

    public List<DiscoveredTool> discover() {
        if (endpoint.isEmpty()) throw new McpFailure("MCP_NOT_CONFIGURED");
        long started = System.nanoTime();
        McpClient client = null;
        try {
            client = clientFactory.create(endpoint, headers, timeout);
            var tools = client.listTools().stream()
                    .filter(tool -> allowedTools.contains(tool.name()))
                    .map(tool -> new DiscoveredTool(tool.name(), tool.description()))
                    .toList();
            log.info("mcp search discovery succeeded: allowedTools={} discovered={} durationMs={}",
                    allowedTools.size(), tools.size(), elapsedMillis(started));
            return tools;
        } catch (Exception failure) {
            log.warn("mcp search discovery failed: code=MCP_UNAVAILABLE type={} durationMs={}",
                    failure.getClass().getSimpleName(), elapsedMillis(started));
            throw new McpFailure("MCP_UNAVAILABLE");
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeFailure) {
                    log.warn("mcp search client close failed: type={}", closeFailure.getClass().getSimpleName());
                }
            }
        }
    }

    /** Executes one fixed, read-only search tool; provider text is accepted only with a valid source URL. */
    public SearchResponse search(String query) {
        if (endpoint.isEmpty() || !allowedTools.contains(SEARCH_TOOL))
            throw new McpFailure("MCP_NOT_CONFIGURED");
        long started = System.nanoTime();
        McpClient client = null;
        try {
            client = clientFactory.create(endpoint, headers, timeout);
            if (client.listTools().stream().noneMatch(tool -> SEARCH_TOOL.equals(tool.name())))
                throw new McpFailure("MCP_SEARCH_TOOL_UNAVAILABLE");
            var arguments = JSON.writeValueAsString(Map.of(
                    "query", query, "search_depth", "basic", "max_results", 5, "include_raw_content", false));
            var result = client.executeTool(ToolExecutionRequest.builder()
                    .name(SEARCH_TOOL).arguments(arguments).build());
            if (result == null || result.isError() || result.resultText() == null)
                throw new McpFailure("MCP_UNAVAILABLE");
            var sources = parseResults(result.resultText());
            log.info("mcp search completed: sources={} durationMs={}", sources.size(), elapsedMillis(started));
            return new SearchResponse(sources.isEmpty() ? "INSUFFICIENT_MATERIAL" : "MATCHED", sources);
        } catch (McpFailure failure) {
            throw failure;
        } catch (Exception failure) {
            log.warn("mcp search failed: code=MCP_UNAVAILABLE type={} durationMs={}",
                    failure.getClass().getSimpleName(), elapsedMillis(started));
            throw new McpFailure("MCP_UNAVAILABLE");
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeFailure) {
                    log.warn("mcp search client close failed: type={}", closeFailure.getClass().getSimpleName());
                }
            }
        }
    }

    private static List<SearchResult> parseResults(String text) {
        if (text.length() > 100_000)
            throw new McpFailure("MCP_INVALID_RESULT");
        if (text.stripLeading().startsWith("{")) return parseJsonResults(text);
        if (!text.contains("Detailed Results:")) throw new McpFailure("MCP_INVALID_RESULT");
        var matcher = RESULT.matcher(text);
        var results = new java.util.ArrayList<SearchResult>();
        while (matcher.find() && results.size() < 3) {
            addResult(results, matcher.group(1), matcher.group(2), matcher.group(3));
        }
        return results;
    }

    private static List<SearchResult> parseJsonResults(String text) {
        try {
            var root = JSON.readTree(text);
            if (root.has("error")) throw new McpFailure("MCP_UNAVAILABLE");
            var entries = root.path("results");
            if (!entries.isArray()) throw new McpFailure("MCP_INVALID_RESULT");
            var results = new java.util.ArrayList<SearchResult>();
            for (var entry : entries) {
                if (results.size() == 3) break;
                addResult(results, entry.path("title").asText(""), entry.path("url").asText(""),
                        entry.path("content").asText(""));
            }
            return results;
        } catch (JsonProcessingException invalid) {
            throw new McpFailure("MCP_INVALID_RESULT");
        }
    }

    private static void addResult(List<SearchResult> results, String title, String url, String content) {
        if (url == null || content == null || content.isBlank()) return;
        try {
            var uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null) return;
        } catch (IllegalArgumentException invalidUrl) {
            return;
        }
        var snippet = content.strip();
        results.add(new SearchResult(url, title == null ? "" : title.strip(),
                snippet.substring(0, Math.min(snippet.length(), 600)), url, null, "WEB"));
    }

    private static McpClient connect(String endpoint, Map<String, String> headers, Duration timeout) {
        var transport = StreamableHttpMcpTransport.builder().url(endpoint).customHeaders(headers)
                .timeout(timeout).logRequests(false).logResponses(false).build();
        return DefaultMcpClient.builder().key("search").transport(transport)
                .initializationTimeout(timeout).protocolDetectionTimeout(timeout)
                .toolExecutionTimeout(timeout).cacheToolList(false).build();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    @FunctionalInterface
    interface ClientFactory {
        McpClient create(String endpoint, Map<String, String> headers, Duration timeout);
    }

    public record DiscoveredTool(String name, String description) { }

    public static final class McpFailure extends RuntimeException {
        McpFailure(String code) { super(code, null, false, false); }
    }
}
