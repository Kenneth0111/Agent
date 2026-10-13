package com.example.creator.content;

import com.example.creator.content.ContentService.GenerationRun;
import com.example.creator.content.ContentService.WeekItem;
import java.util.List;
import java.util.Optional;
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
        when(content.reserveWeekRun(eq(7L), any()))
                .thenReturn(new ContentService.RunReservation(run, true));
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
                "本周三条短视频", null, null, slots, "123e4567-e89b-12d3-a456-426614174000"));

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
        when(content.reserveWeekRun(eq(7L), any()))
                .thenReturn(new ContentService.RunReservation(
                        new GenerationRun("run-2", "account-1", "WEEK_PLAN", "RUNNING", 0, null, null), true));
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
                "本周三条短视频", null, null, slots, "123e4567-e89b-12d3-a456-426614174001"));

        assertThat(result.failedNode()).isEqualTo("slot2/retrieveEvidence");
        verify(content, never()).saveWeekPlan(eq(7L), eq("account-1"), any());
    }

    @Test
    void duplicateWeekRequestReturnsItsExistingRunWithoutCallingTheGraph() {
        var existing = new GenerationRun("run-3", "account-1", "WEEK_PLAN", "SUCCEEDED", 6,
                "week-3", null);
        when(content.reserveWeekRun(eq(7L), any()))
                .thenReturn(new ContentService.RunReservation(existing, false));
        var slots = List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "并发"),
                new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "集合"),
                new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "断句"));

        var result = new GenerationService(content, graph).generate(7,
                new GenerationService.Request("account-1", "WEEK_PLAN", null,
                        "本周三条短视频", null, null, slots, "123e4567-e89b-12d3-a456-426614174002"));

        assertThat(result).isEqualTo(existing);
        verify(graph, never()).run(eq(7L), any());
    }

    @Test
    void resumesAfterCompletedSlotWithoutRegeneratingSavedTopicOrScript() {
        var run = new GenerationRun("run-4", "account-1", "WEEK_PLAN", "RUNNING", 0, null, null);
        when(content.findRun(7, "run-4")).thenReturn(Optional.of(run));
        when(content.weekStages(7, "run-4")).thenReturn(List.of(
                new ContentService.WeekStage(0, "slot1/TOPICS", "COMPLETED", "topic-1", 1),
                new ContentService.WeekStage(1, "slot1/SCRIPT", "COMPLETED", "script-1", 1)));
        when(graph.run(eq(7L), any())).thenReturn(
                new GenerationGraph.Result("topic-2", 1), new GenerationGraph.Result("script-2", 1),
                new GenerationGraph.Result("topic-3", 1), new GenerationGraph.Result("script-3", 1));
        when(content.saveWeekPlan(eq(7L), eq("account-1"), any())).thenReturn("week-4");
        when(content.finishRun(7, "run-4", "week-4", 6)).thenReturn(
                new GenerationRun("run-4", "account-1", "WEEK_PLAN", "SUCCEEDED", 6, "week-4", null));

        var result = new GenerationService(content, graph).resumeWeek(7, "run-4", request()).orElseThrow();

        assertThat(result.resultId()).isEqualTo("week-4");
        verify(graph, times(4)).run(eq(7L), any());
        var saved = ArgumentCaptor.forClass(List.class);
        verify(content).saveWeekPlan(eq(7L), eq("account-1"), saved.capture());
        assertThat((List<WeekItem>) saved.getValue()).containsExactly(
                new WeekItem("Java 面试", "topic-1", "script-1"),
                new WeekItem("Java 面试", "topic-2", "script-2"),
                new WeekItem("英语跟读", "topic-3", "script-3"));
    }

    @Test
    void doesNotReplayAStartedStageWithUnknownModelOutcome() {
        when(content.findRun(7, "run-5")).thenReturn(Optional.of(
                new GenerationRun("run-5", "account-1", "WEEK_PLAN", "RUNNING", 0, null, null)));
        when(content.weekStages(7, "run-5")).thenReturn(List.of(
                new ContentService.WeekStage(0, "slot1/TOPICS", "COMPLETED", "topic-1", 1),
                new ContentService.WeekStage(1, "slot1/SCRIPT", "STARTED", null, 0)));

        assertThat(new GenerationService(content, graph).resumeWeek(7, "run-5", request())).isEmpty();
        verify(graph, never()).run(eq(7L), any());
    }

    @Test
    void completedWeekCheckpointOnlyFinalizesTheRun() {
        when(content.findRun(7, "run-6")).thenReturn(Optional.of(
                new GenerationRun("run-6", "account-1", "WEEK_PLAN", "RUNNING", 0, null, null)));
        when(content.weekStages(7, "run-6")).thenReturn(List.of(
                new ContentService.WeekStage(0, "slot1/TOPICS", "COMPLETED", "topic-1", 1),
                new ContentService.WeekStage(1, "slot1/SCRIPT", "COMPLETED", "script-1", 1),
                new ContentService.WeekStage(2, "slot2/TOPICS", "COMPLETED", "topic-2", 1),
                new ContentService.WeekStage(3, "slot2/SCRIPT", "COMPLETED", "script-2", 1),
                new ContentService.WeekStage(4, "slot3/TOPICS", "COMPLETED", "topic-3", 1),
                new ContentService.WeekStage(5, "slot3/SCRIPT", "COMPLETED", "script-3", 1),
                new ContentService.WeekStage(6, "saveWeekPlan", "COMPLETED", "week-6", 0)));
        when(content.finishRun(7, "run-6", "week-6", 6)).thenReturn(
                new GenerationRun("run-6", "account-1", "WEEK_PLAN", "SUCCEEDED", 6, "week-6", null));

        assertThat(new GenerationService(content, graph).resumeWeek(7, "run-6", request()).orElseThrow()
                .resultId()).isEqualTo("week-6");
        verify(graph, never()).run(eq(7L), any());
        verify(content, never()).saveWeekPlan(eq(7L), eq("account-1"), any());
    }

    private GenerationService.Request request() {
        return new GenerationService.Request("account-1", "WEEK_PLAN", null, "本周三条短视频", null,
                null, List.of(new GenerationService.WeekSlot("Java 面试", List.of("m-1"), "并发"),
                        new GenerationService.WeekSlot("Java 面试", List.of("m-2"), "集合"),
                        new GenerationService.WeekSlot("英语跟读", List.of("m-3"), "断句")),
                "123e4567-e89b-12d3-a456-426614174003");
    }
}
