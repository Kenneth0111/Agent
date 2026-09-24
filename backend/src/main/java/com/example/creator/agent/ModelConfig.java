package com.example.creator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {
    @Bean
    ModelGateway modelGateway(@Value("${creator.model.base-url}") String baseUrl,
                              @Value("${creator.model.api-key:}") String apiKey,
                              @Value("${creator.model.name}") String modelName,
                              @Value("${creator.model.timeout}") Duration timeout,
                              ObjectMapper json) {
        var model = apiKey.isBlank() ? null : createModel(baseUrl, apiKey, modelName, timeout);
        return new ModelGateway(model, json);
    }

    static ChatModel createModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        // Paid calls must not be repeated implicitly; retries are decided by the caller's task state.
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .timeout(timeout).maxRetries(0).maxTokens(1024)
                .logRequests(false).logResponses(false)
                .build();
    }
}
