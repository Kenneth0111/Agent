package com.example.creator.content;

import com.example.creator.agent.ModelGateway;
import com.example.creator.content.ContentService.SavedScript;
import com.example.creator.content.ContentService.SavedTopic;
import com.example.creator.content.ContentValidator.Script;
import com.example.creator.content.ContentValidator.Topic;
import com.example.creator.material.MaterialService;
import com.example.creator.material.MaterialService.MaterialDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {
    @Mock ContentService content;
    @Mock MaterialService materials;
    @Mock ModelGateway model;
    final SavedScript original = new SavedScript("script-1", "topic-1",
            new Script("原稿：展示 IDE 录屏。", "录屏", List.of("m-1")), "DRAFT", 1);
    final SavedTopic topic = new SavedTopic("topic-1", "account-1",
            new Topic("Java 面试", "并发", "程序员", "可见性", "问题", "简答", List.of("m-1"), "资料"));

    @Test
    void confirmedScriptCannotSpendAnotherModelCallBeforeReopening() {
        var confirmed = new SavedScript("script-1", "topic-1", original.script(), "CONFIRMED", 2);
        when(content.findScript(7, "script-1")).thenReturn(Optional.of(confirmed));
        var service = new ConversationService(content, materials, model,
                new ContentValidator(new ObjectMapper()));

        assertThatThrownBy(() -> service.revise(7, "script-1",
                new ConversationService.Revision(null, 2, "改口播")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SCRIPT_CONFIRMED");
        verify(model, never()).replyJson(any());
    }

    @Test
    void revisesOnlyOwnedScriptAndPreservesTheSameSource() {
        when(content.findScript(7, "script-1")).thenReturn(Optional.of(original));
        when(content.findTopic(7, "topic-1")).thenReturn(Optional.of(topic));
        when(materials.find(7, "m-1")).thenReturn(Optional.of(new MaterialDetail("m-1", "规范", "参考",
                null, null, 1, "volatile 保证可见性。", "TEXT", List.of("account-1"))));
        when(model.replyJson(any())).thenReturn("""
                {"spokenText":"新版：正面口播解释可见性。","shootingNotes":"口播","sourceIds":["m-1"]}
                """);
        var revised = new SavedScript("script-1", "topic-1",
                new Script("新版：正面口播解释可见性。", "口播", List.of("m-1")), "DRAFT", 2, "conversation-1");
        when(content.reviseScript(eq(7L), eq("script-1"), eq(1), eq(null), eq("改成口播"), any()))
                .thenReturn(revised);
        var service = new ConversationService(content, materials, model,
                new ContentValidator(new ObjectMapper()));

        var result = service.revise(7, "script-1", new ConversationService.Revision(null, 1, "改成口播"));

        assertThat(result.version()).isEqualTo(2);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(model).replyJson(prompt.capture());
        assertThat(prompt.getValue()).contains("改成口播", "原稿：展示 IDE 录屏", "资料 ID：m-1",
                "问题、简答、解释、代码或演示步骤、追问");
        verify(content).reviseScript(eq(7L), eq("script-1"), eq(1), eq(null), eq("改成口播"),
                eq(revised.script()));
    }

    @Test
    void rejectsForeignConversationBeforeReadingEvidenceOrCallingModel() {
        when(content.findScript(7, "script-1")).thenReturn(Optional.of(original));
        when(content.findTopic(7, "topic-1")).thenReturn(Optional.of(topic));
        when(content.ownsConversation(7, "account-1", "foreign-session")).thenReturn(false);
        var service = new ConversationService(content, materials, model,
                new ContentValidator(new ObjectMapper()));

        assertThatThrownBy(() -> service.revise(7, "script-1",
                new ConversationService.Revision("foreign-session", 1, "改口播")))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("CONVERSATION_NOT_FOUND");
        verify(materials, never()).find(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(model, never()).replyJson(any());
    }
}
