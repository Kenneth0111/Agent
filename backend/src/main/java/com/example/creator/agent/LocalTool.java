package com.example.creator.agent;

import dev.langchain4j.agent.tool.ToolSpecification;

/** A read-only capability the model may call. Arguments come from the model and are never trusted. */
public interface LocalTool {
    ToolSpecification specification();

    /** @return text handed back to the model; must not contain data outside the caller's scope. */
    String execute(String argumentsJson);

    default String name() {
        return specification().name();
    }

    /** Rejections are reported to the model as a result, so the run can end with an explanation. */
    final class ToolRejection extends RuntimeException {
        ToolRejection(String code) {
            super(code, null, false, false);
        }
    }
}
