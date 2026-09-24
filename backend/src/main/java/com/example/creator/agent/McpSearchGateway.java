package com.example.creator.agent;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Discovers only explicitly allowed search tools; it does not expose user data to MCP servers. */
public class McpSearchGateway {
    private static final Logger log = LoggerFactory.getLogger(McpSearchGateway.class);

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
