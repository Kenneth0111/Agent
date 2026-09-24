package com.example.creator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.Map;

/**
 * Reads one account configuration. The owner is fixed at construction from the login context, so a
 * model-supplied identifier can never widen the scope beyond that user's own accounts.
 */
public class AccountProfileTool implements LocalTool {
    private static final ToolSpecification SPECIFICATION = ToolSpecification.builder()
            .name("read_account_profile")
            .description("读取当前用户某个内容账号的定位、栏目和每周条数。只读，不修改任何数据。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("accountId", "账号 ID")
                    .required("accountId")
                    .build())
            .build();

    private final AccountProfiles accounts;
    private final ObjectMapper json;
    private final long ownerId;

    public AccountProfileTool(AccountProfiles accounts, ObjectMapper json, long ownerId) {
        this.accounts = accounts;
        this.json = json;
        this.ownerId = ownerId;
    }

    @Override
    public ToolSpecification specification() {
        return SPECIFICATION;
    }

    @Override
    public String execute(String argumentsJson) {
        var accountId = requestedAccountId(argumentsJson);
        // Not-owned and not-existing are the same answer: the model learns nothing about other users.
        var profile = accounts.find(ownerId, accountId)
                .orElseThrow(() -> new ToolRejection("ACCOUNT_NOT_FOUND"));
        try {
            return json.writeValueAsString(Map.of(
                    "accountId", profile.id(), "name", profile.name(), "audience", profile.audience(),
                    "positioning", profile.positioning(), "columns", profile.columns(),
                    "weeklyTarget", profile.weeklyTarget()));
        } catch (JsonProcessingException unexpected) {
            throw new ToolRejection("TOOL_RESULT_UNSERIALIZABLE");
        }
    }

    private String requestedAccountId(String argumentsJson) {
        try {
            var accountId = json.readTree(argumentsJson == null ? "" : argumentsJson).path("accountId");
            if (!accountId.isTextual() || accountId.asText().isBlank()) {
                throw new ToolRejection("TOOL_ARGUMENTS_INVALID");
            }
            return accountId.asText().strip();
        } catch (JsonProcessingException invalid) {
            throw new ToolRejection("TOOL_ARGUMENTS_INVALID");
        }
    }
}
