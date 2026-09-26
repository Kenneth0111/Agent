package com.example.creator.agent;

import com.example.creator.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Without credentials the application still starts and the gateway refuses calls explicitly. */
@SpringBootTest(properties = "creator.model.api-key=")
class ModelWiringTest extends IntegrationTestSupport {
    @Autowired private ModelGateway gateway;

    @Test
    void anUnconfiguredProviderIsReportedInsteadOfFailingStartup() {
        assertThatThrownBy(() -> gateway.reply("测试"))
                .isInstanceOf(ModelGateway.ModelFailure.class).hasMessage("MODEL_NOT_CONFIGURED");
    }
}
