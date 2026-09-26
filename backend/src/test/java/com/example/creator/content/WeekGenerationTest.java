package com.example.creator.content;

import com.example.creator.content.ContentService.GenerationRun;
import com.example.creator.content.ContentService.WeekItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class WeekGenerationTest {
    @Mock ContentService content;
    @Mock GenerationGraph graph;

    @Test
    void weeklyRunProducesTwoJavaAndOneEnglishDraftOnlyAfterAllSixStagesSucceed() {
        var run = new GenerationRun("run-1", "account-1", "WEEK_PLAN", "RUNNING", 0, null, null);
        when(content.startRun(7, "account-1", "WEEK_PLAN")).thenReturn(run);
        when(graph.run(eq(7L), any())).thenReturn(
                new GenerationGraph.Result("topic-1", 1), new GenerationGraph.Result("script-1", 1),
                new GenerationGraph.Result("topic-2", 1), new GenerationGraph.Result("script-2", 1),
                new GenerationGraph.Result("topic-3", 1), new GenerationGraph.Result("script-3", 1));
        when(content.saveWeekPlan(eq(7L), eq("account-1"), any())).thenReturn("week-1");
        when(content.finishRun(7, "run-1", "week-1", 6))
                .thenReturn(new GenerationRun("run-1", "account-1", "WEEK_PLAN", "SUCCEEDED", 6, "week-1", null));
        var service = new GenerationService(content, graph);
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "并发"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "集合"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "断句"));

        var result = service.generate(7, new GenerationService.Request("account-1", "WEEK_PLAN", null,
                "本周三条短视频", null, null, slots));

        assertThat(result.resultId()).isEqualTo("week-1");
        var requests = ArgumentCaptor.forClass(GenerationService.Request.class);
        verify(graph, times(6)).run(eq(7L), requests.capture());
        assertThat(requests.getAllValues().stream().map(GenerationService.Request::mode))
                .containsExactly("TOPICS", "SCRIPT", "TOPICS", "SCRIPT", "TOPICS", "SCRIPT");
        assertThat(requests.getAllValues().get(4).materialIds()).containsExactly("m-3");
        var saved = ArgumentCaptor.forClass(List.class);
        verify(content).saveWeekPlan(eq(7L), eq("account-1"), saved.capture());
        assertThat((List<WeekItem>) saved.getValue()).containsExactly(
                new WeekItem("Java 面试", "topic-1", "script-1"),
                new WeekItem("Java 面试", "topic-2", "script-2"),
                new WeekItem("英语跟读", "topic-3", "script-3"));
    }

    @Test
    void failingSecondSlotNamesTheFailedNodeAndDoesNotSaveAWeek() {
        when(content.startRun(7, "account-1", "WEEK_PLAN"))
                .thenReturn(new GenerationRun("run-2", "account-1", "WEEK_PLAN", "RUNNING", 0, null, null));
        when(graph.run(eq(7L), any())).thenReturn(new GenerationGraph.Result("topic-1", 1),
                new GenerationGraph.Result("script-1", 1))
                .thenThrow(new GenerationGraph.StageFailure("retrieveEvidence", "MATERIAL_NOT_FOUND", 0));
        when(content.failRun(7, "run-2", "MATERIAL_NOT_FOUND", "slot2/retrieveEvidence", 2))
                .thenReturn(new GenerationRun("run-2", "account-1", "WEEK_PLAN", "FAILED", 2,
                        null, "MATERIAL_NOT_FOUND", "slot2/retrieveEvidence"));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "并发"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "集合"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "断句"));

        var result = new GenerationService(content, graph).generate(7,
                new GenerationService.Request("account-1", "WEEK_PLAN", null,
                        "本周三条短视频", null, null, slots));

        assertThat(result.failedNode()).isEqualTo("slot2/retrieveEvidence");
        verify(content, never()).saveWeekPlan(eq(7L), eq("account-1"), any());
    }
}
