package com.example.creator.agent;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpSearchConfig {
    @Bean
    McpSearchGateway mcpSearchGateway(@Value("${creator.mcp.search.url:}") String endpoint,
                                      @Value("${creator.mcp.search.bearer-token:}") String bearerToken,
                                      @Value("${creator.mcp.search.allowed-tools:tavily_search}") String allowedTools,
                                      @Value("${creator.mcp.search.timeout:10s}") Duration timeout) {
        var allowList = Arrays.stream(allowedTools.split(",")).map(String::strip)
                .filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
        var headers = bearerToken.isBlank() ? Map.<String, String>of()
                : Map.of("Authorization", "Bearer " + bearerToken);
        return new McpSearchGateway(endpoint, headers, allowList, timeout);
    }
}
