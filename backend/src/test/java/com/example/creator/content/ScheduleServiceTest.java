package com.example.creator.content;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "DEV_USER_PASSWORD=development-fixture-only")
@ActiveProfiles("dev")
class ScheduleServiceTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accounts;
    @Autowired private ScheduleService schedules;

    @Test
    void createsOwnedWeekWithConfiguredQuotaAndEditableDates() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("周排期测试", "程序员",
                "Java 面试和英语跟读", List.of("Java 面试", "英语跟读"), 3));
        var monday = LocalDate.of(2026, 10, 5);
        var first = schedules.create(owner, account.id(), monday);
        assertThat(first.items()).extracting(ScheduleService.Item::column)
                .containsExactly("Java 面试", "Java 面试", "英语跟读");
        assertThat(first.items()).extracting(ScheduleService.Item::scheduledDate)
                .containsExactly(monday, monday.plusDays(3), monday.plusDays(6));
        assertThat(first.items()).allSatisfy(item -> {
            assertThat(item.topicId()).isNull();
            assertThat(item.scriptId()).isNull();
        });
        assertThat(schedules.find(stranger, first.id())).isEmpty();
        assertThatThrownBy(() -> schedules.create(stranger, account.id(), monday))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("ACCOUNT_NOT_FOUND");

        var moved = schedules.move(owner, first.items().getFirst().id(), 1, monday.plusDays(1));
        assertThat(moved.scheduledDate()).isEqualTo(monday.plusDays(1));
        assertThat(moved.version()).isEqualTo(2);
        assertThatThrownBy(() -> schedules.move(owner, moved.id(), 1, monday.plusDays(2)))
                .isInstanceOf(ContentService.VersionConflict.class);
        assertThatThrownBy(() -> schedules.move(stranger, moved.id(), 2, monday.plusDays(2)))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("PLAN_ITEM_NOT_FOUND");
        assertThatThrownBy(() -> schedules.move(owner, moved.id(), 2, monday.plusWeeks(1)))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("INVALID_SCHEDULE_DATE");

        accounts.update(owner, account.id(), new AccountService.AccountInput("周排期测试", "程序员",
                "Java 面试和英语跟读", List.of("Java 面试", "英语跟读"), 4));
        var next = schedules.create(owner, account.id(), monday.plusWeeks(1));
        assertThat(next.items()).hasSize(4).extracting(ScheduleService.Item::column)
                .containsExactly("Java 面试", "Java 面试", "Java 面试", "英语跟读");
        assertThat(schedules.find(owner, first.id())).get().extracting(ScheduleService.Week::items)
                .asList().hasSize(3);
        assertThat(schedules.create(owner, account.id(), monday).id()).isEqualTo(first.id());
    }
}
