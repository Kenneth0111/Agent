package com.example.creator.agent;

import com.example.creator.material.MaterialSearchService.SearchResponse;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

/** One account-scoped research run. IDs are supplied by the authenticated entry point. */
public class ResearchState extends AgentState {
    public ResearchState(Map<String, Object> data) { super(data); }

    public long userId() { return this.<Number>value("userId").orElseThrow().longValue(); }
    public String accountId() { return this.<String>value("accountId").orElseThrow(); }
    public String query() { return this.<String>value("query").orElseThrow(); }
    public Optional<SearchResponse> localEvidence() { return value("localEvidence"); }
    public Optional<SearchResponse> webEvidence() { return value("webEvidence"); }
    public Optional<String> webSearchStatus() { return value("webSearchStatus"); }
    public SearchResponse evidence() { return webEvidence().orElseGet(() -> localEvidence().orElseThrow()); }
    public Optional<String> answer() { return value("answer"); }
}
