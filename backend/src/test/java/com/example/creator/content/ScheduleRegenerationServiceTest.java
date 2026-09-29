package com.example.creator.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class ScheduleRegenerationServiceTest {
    @Mock ScheduleService schedules;
    @Mock ContentService content;
    @Mock GenerationGraph graph;

    @Test
    void regeneratesOnlyTheSelectedSlotAndReplacesItsDraft() {
        var old = new ScheduleService.Item("item-1", "Java 面试", LocalDate.of(2026, 10, 5),
                null, null, 2);
        var updated = new ScheduleService.Item("item-1", "Java 面试", old.scheduledDate(),
                "topic-new", "script-new", 3);
        when(schedules.slot(7, "item-1"))
                .thenReturn(Optional.of(new ScheduleService.Slot("account-1", old)));
        when(graph.run(eq(7L), any())).thenReturn(new GenerationGraph.Result("topic-new", 1),
                new GenerationGraph.Result("script-new", 1));
        when(schedules.replaceDraft(7, "item-1", 2, "topic-new", "script-new"))
                .thenReturn(updated);

        var result = new ScheduleRegenerationService(schedules, content, graph).regenerate(7,
                "item-1", new ScheduleRegenerationService.Input(2, "重新讲可见性", List.of("m-1")));

        assertThat(result).isEqualTo(updated);
        verify(schedules).replaceDraft(7, "item-1", 2, "topic-new", "script-new");
        verify(graph, org.mockito.Mockito.times(2)).run(eq(7L), any());
    }

    @Test
    void confirmedSlotIsRejectedBeforeModelCalls() {
        var old = new ScheduleService.Item("item-1", "Java 面试", LocalDate.of(2026, 10, 5),
                "topic-old", "script-old", 2);
        when(schedules.slot(7, "item-1"))
                .thenReturn(Optional.of(new ScheduleService.Slot("account-1", old)));
        when(content.findScript(7, "script-old"))
                .thenReturn(Optional.of(new ContentService.SavedScript("script-old", "topic-old",
                        new ContentValidator.Script("确认稿", "录屏", List.of("m-1")), "CONFIRMED", 2)));

        assertThatThrownBy(() -> new ScheduleRegenerationService(schedules, content, graph).regenerate(7,
                "item-1", new ScheduleRegenerationService.Input(2, "重做", List.of("m-1"))))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SCRIPT_CONFIRMED");
        verify(graph, never()).run(eq(7L), any());
    }
}
