package com.example.creator.agent;

import com.example.creator.material.MaterialSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/** Read-only search fixed to the signed-in user and selected account before the model can call it. */
public class MaterialTools implements LocalTool {
    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name("search_my_materials")
            .description("检索当前用户所选账号可用的个人资料，返回原文片段和出处；没有结果时返回资料不足。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("query", "要检索的关键词或短语，最多 100 字")
                    .required("query")
                    .build())
            .build();

    private final MaterialSearchService search;
    private final ObjectMapper json;
    private final long ownerId;
    private final String accountId;

    public MaterialTools(MaterialSearchService search, ObjectMapper json, long ownerId, String accountId) {
        this.search = search;
        this.json = json;
        this.ownerId = ownerId;
        this.accountId = accountId;
    }

    @Override
    public ToolSpecification specification() { return SPECIFICATION; }

    @Override
    public String execute(String argumentsJson) {
        String query;
        try {
            var arguments = json.readTree(argumentsJson == null ? "" : argumentsJson);
            if (arguments == null || !arguments.path("query").isTextual())
                throw new ToolRejection("TOOL_ARGUMENTS_INVALID");
            query = arguments.path("query").asText();
        } catch (JsonProcessingException invalid) {
            throw new ToolRejection("TOOL_ARGUMENTS_INVALID");
        }
        try {
            return json.writeValueAsString(search.search(ownerId, accountId, query));
        } catch (MaterialSearchService.SearchFailure failure) {
            throw new ToolRejection(failure.getMessage());
        } catch (JsonProcessingException impossible) {
            throw new ToolRejection("TOOL_RESULT_UNSERIALIZABLE");
        }
    }
}
