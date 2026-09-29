package com.example.creator.content;

import com.example.creator.IntegrationTestSupport;
import com.example.creator.account.AccountService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
    @Autowired private ContentService content;

    @Test
    void attachesOneOwnedDraftBatchWithoutReplacingAssignedItems() {
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-a@example.test");
        long stranger = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "creator-b@example.test");
        var account = accounts.create(owner, new AccountService.AccountInput("绑定草稿", "程序员",
                "Java 和英语", List.of("Java 面试", "英语跟读"), 3));
        var week = schedules.create(owner, account.id(), LocalDate.of(2026, 10, 5));
        var items = new java.util.ArrayList<ContentService.WeekItem>();
        for (int i = 0; i < 3; i++) {
            var column = i == 2 ? "英语跟读" : "Java 面试";
            var topicId = content.saveTopic(owner, account.id(), new ContentValidator.Topic(column,
                    "选题" + i, "程序员", "角度", "开头", "提纲", List.of("m-1"), "资料"), Set.of("m-1"));
            var scriptId = content.saveScript(owner, topicId,
                    new ContentValidator.Script("脚本" + i, "录屏", List.of("m-1")), Set.of("m-1"));
            items.add(new ContentService.WeekItem(column, topicId, scriptId));
        }
        var batchId = content.saveWeekPlan(owner, account.id(), items);
        assertThatThrownBy(() -> schedules.attachBatch(stranger, week.id(), batchId))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("PLAN_NOT_FOUND");

        var attached = schedules.attachBatch(owner, week.id(), batchId);
        assertThat(schedules.slot(stranger, attached.items().getFirst().id())).isEmpty();
        assertThatThrownBy(() -> schedules.replaceDraft(stranger, attached.items().getFirst().id(), 2,
                items.getFirst().topicId(), items.getFirst().scriptId()))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("PLAN_ITEM_NOT_FOUND");
        assertThat(attached.items()).extracting(ScheduleService.Item::scriptId)
                .containsExactlyElementsOf(items.stream().map(ContentService.WeekItem::scriptId).toList());
        assertThat(schedules.attachBatch(owner, week.id(), batchId)).isEqualTo(attached);
        assertThatThrownBy(() -> schedules.changeColumn(owner, attached.items().getFirst().id(), 2, "英语跟读"))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("PLAN_ITEM_OCCUPIED");
        var replacementTopic = content.saveTopic(owner, account.id(), new ContentValidator.Topic("Java 面试",
                "替换选题", "程序员", "新角度", "开头", "提纲", List.of("m-1"), "资料"), Set.of("m-1"));
        var replacementScript = content.saveScript(owner, replacementTopic,
                new ContentValidator.Script("替换口播稿", "录屏", List.of("m-1")), Set.of("m-1"));
        var replaced = schedules.replaceDraft(owner, attached.items().getFirst().id(), 2,
                replacementTopic, replacementScript);
        assertThat(replaced.version()).isEqualTo(3);
        assertThat(schedules.find(owner, week.id()).orElseThrow().items())
                .extracting(ScheduleService.Item::scriptId)
                .containsExactly(replacementScript, items.get(1).scriptId(), items.get(2).scriptId());
        assertThatThrownBy(() -> schedules.replaceDraft(owner, replaced.id(), 2,
                replacementTopic, replacementScript)).isInstanceOf(ContentService.VersionConflict.class);
        content.confirmScript(owner, items.get(1).scriptId(), 1);
        assertThatThrownBy(() -> schedules.replaceDraft(owner, attached.items().get(1).id(), 2,
                replacementTopic, replacementScript))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SCRIPT_CONFIRMED");
        assertThatThrownBy(() -> schedules.setPublication(owner, replaced.id(), 3, true,
                "https://www.douyin.com/video/123", null))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SCRIPT_NOT_CONFIRMED");
        assertThatThrownBy(() -> schedules.setPublication(owner, attached.items().get(1).id(), 2,
                true, null, null)).isInstanceOf(ContentValidator.ContentInvalid.class)
                .hasMessage("INVALID_PUBLICATION");
        assertThatThrownBy(() -> schedules.setPublication(owner, attached.items().get(1).id(), 2,
                true, "javascript:alert(1)", null)).isInstanceOf(ContentValidator.ContentInvalid.class)
                .hasMessage("INVALID_PUBLICATION");
        var published = schedules.setPublication(owner, attached.items().get(1).id(), 2, true,
                "https://www.douyin.com/video/123", "123");
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publicationUrl()).isEqualTo("https://www.douyin.com/video/123");
        assertThat(published.externalWorkId()).isEqualTo("123");
        assertThat(published.scriptId()).isEqualTo(items.get(1).scriptId());
        assertThatThrownBy(() -> content.reopenScript(owner, published.scriptId(), 2))
                .isInstanceOf(ContentValidator.ContentInvalid.class).hasMessage("SCRIPT_ALREADY_PUBLISHED");
        assertThatThrownBy(() -> schedules.setPublication(owner, published.id(), 2, false,
                null, null)).isInstanceOf(ContentService.VersionConflict.class);
        assertThatThrownBy(() -> schedules.setPublication(stranger, published.id(), 3, false,
                null, null)).isInstanceOf(ContentValidator.ContentInvalid.class)
                .hasMessage("PLAN_ITEM_NOT_FOUND");
        var unpublished = schedules.setPublication(owner, published.id(), 3, false, null, null);
        assertThat(unpublished.publishedAt()).isNull();
        assertThat(unpublished.scriptId()).isEqualTo(items.get(1).scriptId());
        assertThat(content.reopenScript(owner, unpublished.scriptId(), 2).status()).isEqualTo("DRAFT");
    }

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
        var changedColumn = schedules.changeColumn(owner, next.items().getFirst().id(), 1, "英语跟读");
        assertThat(changedColumn.column()).isEqualTo("英语跟读");
        assertThat(changedColumn.version()).isEqualTo(2);
        assertThat(schedules.find(owner, first.id())).get().extracting(ScheduleService.Week::items)
                .asList().hasSize(3);
        assertThat(schedules.create(owner, account.id(), monday).id()).isEqualTo(first.id());
    }
}
