package com.example.creator.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentValidatorTest {
    private final ContentValidator validator = new ContentValidator(new ObjectMapper());
    private static final String VALID_TOPIC = """
            {"column":"Java 面试","title":"volatile 快问快答","audience":"Java 开发者",
             "angle":"澄清可见性与原子性的区别","hook":"volatile 能保证线程安全吗？",
             "outline":"问题、简答、追问","sourceIds":["material-1"],"rationale":"引用并发笔记"}
            """;

    @Test
    void missingFieldsAndUnseenSourcesCannotBecomeTopics() {
        assertThat(validator.topic(VALID_TOPIC, Set.of("material-1")).sourceIds())
                .containsExactly("material-1");
        assertThatThrownBy(() -> validator.topic("{\"title\":\"缺字段\"}", Set.of("material-1")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("CONTENT_INVALID");
        assertThatThrownBy(() -> validator.topic(VALID_TOPIC.replace("material-1", "foreign-material"),
                Set.of("material-1")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SOURCE_NOT_IN_EVIDENCE");
    }

    @Test
    void boundedOutlineLinesAreJoinedWithoutRelaxingSourceValidation() {
        var arrayOutline = VALID_TOPIC.replace("\"outline\":\"问题、简答、追问\"",
                "\"outline\":[\"问题\",\"简答\",\"追问\"]");
        assertThat(validator.topic(arrayOutline, Set.of("material-1")).outline())
                .isEqualTo("问题\n简答\n追问");
        assertThatThrownBy(() -> validator.topic(arrayOutline.replace("\"简答\"", "42"), Set.of("material-1")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("CONTENT_INVALID");
    }

    @Test
    void emptyScriptAndUnseenCitationAreRejected() {
        assertThatThrownBy(() -> validator.script("""
                {"spokenText":" ","shootingNotes":"录屏","sourceIds":["material-1"]}
                """, Set.of("material-1")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("CONTENT_INVALID");
        assertThatThrownBy(() -> validator.script("""
                {"spokenText":"解释 volatile","shootingNotes":"录屏","sourceIds":["wrong"]}
                """, Set.of("material-1")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SOURCE_NOT_IN_EVIDENCE");
    }
}
