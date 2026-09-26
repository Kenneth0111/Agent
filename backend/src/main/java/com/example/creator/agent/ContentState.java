package com.example.creator.agent;

import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

/** Stage data of one workflow run. The owner is part of the state and is never taken from the model. */
public class ContentState extends AgentState {
    public ContentState(Map<String, Object> data) {
        super(data);
    }

    public long userId() {
        return this.<Number>value("userId")
                .orElseThrow(() -> new IllegalStateException("userId is required")).longValue();
    }

    public String accountId() {
        return this.<String>value("accountId")
                .orElseThrow(() -> new IllegalStateException("accountId is required"));
    }

    public Optional<String> accountFacts() {
        return value("accountFacts");
    }

    public Optional<String> summary() {
        return value("summary");
    }
}
