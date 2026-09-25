package com.example.creator.content;

import com.example.creator.agent.AccountProfile;
import com.example.creator.agent.AccountProfiles;
import com.example.creator.agent.ModelGateway;
import com.example.creator.content.ContentService.GenerationRun;
import com.example.creator.content.ContentService.SavedTopic;
import com.example.creator.content.ContentValidator.Topic;
import com.example.creator.material.MaterialSearchService;
import com.example.creator.material.MaterialService;
import com.example.creator.material.MaterialService.MaterialDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationServiceTest {
    @Mock AccountProfiles accounts;
    @Mock MaterialService materials;
    @Mock MaterialSearchService search;
    @Mock ModelGateway model;
    @Mock ContentService content;
    GenerationService service;
    final AccountProfile account = new AccountProfile("account-1", 7, "新号", "程序员", "Java 八股快问快答与英语跟读",
            List.of("Java 面试", "英语跟读"), 3);
    final MaterialDetail material = new MaterialDetail("m-1", "Java 规范", "学习参考", "https://example.test/spec",
            null, 1, "volatile 字段写入与后续读取之间存在 happens-before 关系。", "TEXT", List.of("account-1"));

    @BeforeEach
    void setUp() {
        service = new GenerationService(accounts, materials, search, model,
                new ContentValidator(new ObjectMapper()), content);
    }

    @Test
    void javaTopicUsesOnlyOwnedEvidenceAndPersistsValidatedResult() {
        when(accounts.find(7, "account-1")).thenReturn(Optional.of(account));
        when(content.startRun(7, "account-1", "TOPICS"))
                .thenReturn(new GenerationRun("run-1", "account-1", "TOPICS", "RUNNING", 0, null, null));
        when(materials.find(7, "m-1")).thenReturn(Optional.of(material));
        when(model.replyJson(any())).thenReturn("""
                {"column":"Java 面试","title":"volatile 快问快答","audience":"程序员",
                "angle":"并发可见性","hook":"volatile 能保证什么？","outline":"问题、简答、解释、示例和追问",
                "sourceIds":["m-1"],"rationale":"根据规范片段"}
                """);
        when(content.saveTopic(eq(7L), eq("account-1"), any(), eq(Set.of("m-1")))).thenReturn("topic-1");
        when(content.finishRun(7, "run-1", "topic-1", 1))
                .thenReturn(new GenerationRun("run-1", "account-1", "TOPICS", "SUCCEEDED", 1, "topic-1", null));

        var run = service.generate(7, new GenerationService.Request("account-1", "TOPICS", "Java 面试",
                "volatile", List.of("m-1"), null));

        assertThat(run.status()).isEqualTo("SUCCEEDED");
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(model).replyJson(prompt.capture());
        assertThat(prompt.getValue()).contains("资料 ID：m-1", "Java 面试", "不要虚构来源");
        verify(content).saveTopic(eq(7L), eq("account-1"), any(), eq(Set.of("m-1")));
    }

    @Test
    void foreignAccountMaterialStopsBeforeModelCallAndRecordsFailure() {
        when(accounts.find(7, "account-1")).thenReturn(Optional.of(account));
        when(content.startRun(7, "account-1", "TOPICS"))
                .thenReturn(new GenerationRun("run-1", "account-1", "TOPICS", "RUNNING", 0, null, null));
        when(materials.find(7, "m-1")).thenReturn(Optional.of(new MaterialDetail("m-1", "隔离资料", "参考",
                null, null, 1, "secret", "TEXT", List.of("account-2"))));
        when(content.failRun(7, "run-1", "MATERIAL_NOT_FOUND", 0))
                .thenReturn(new GenerationRun("run-1", "account-1", "TOPICS", "FAILED", 0, null, "MATERIAL_NOT_FOUND"));

        var run = service.generate(7, new GenerationService.Request("account-1", "TOPICS", "Java 面试",
                "并发", List.of("m-1"), null));

        assertThat(run.errorCode()).isEqualTo("MATERIAL_NOT_FOUND");
        verify(model, never()).replyJson(any());
    }

    @Test
    void englishScriptUsesSavedTopicSourcesAndDoesNotInventOriginalPassage() {
        when(accounts.find(7, "account-1")).thenReturn(Optional.of(account));
        when(content.startRun(7, "account-1", "SCRIPT"))
                .thenReturn(new GenerationRun("run-2", "account-1", "SCRIPT", "RUNNING", 0, null, null));
        var topic = new Topic("英语跟读", "短材料跟读", "托福备考者", "断句", "跟读挑战",
                "开头、短材料、表达、收尾", List.of("m-1"), "用户提供");
        when(content.findTopic(7, "topic-1")).thenReturn(Optional.of(new SavedTopic("topic-1", "account-1", topic)));
        when(materials.find(7, "m-1")).thenReturn(Optional.of(material));
        when(model.replyJson(any())).thenReturn("""
                {"spokenText":"开头：今天练短材料。断句后跟读，最后复述。", "shootingNotes":"口播并配字幕；用户提供资料，仅练习使用", "sourceIds":["m-1"]}
                """);
        when(content.saveScript(eq(7L), eq("topic-1"), any(), eq(Set.of("m-1")))).thenReturn("script-1");
        when(content.finishRun(7, "run-2", "script-1", 1))
                .thenReturn(new GenerationRun("run-2", "account-1", "SCRIPT", "SUCCEEDED", 1, "script-1", null));

        var run = service.generate(7, new GenerationService.Request("account-1", "SCRIPT", null,
                "30 秒跟读", null, "topic-1"));

        assertThat(run.resultId()).isEqualTo("script-1");
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(model).replyJson(prompt.capture());
        assertThat(prompt.getValue()).contains("英语跟读", "托福官方评分", "只引用提供的短片段");
    }
}
