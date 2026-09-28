package com.example.creator.content;

import com.example.creator.agent.AccountProfiles;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores a week's intended slots independently of generated draft batches. */
@Service
public class ScheduleService {
    private final JdbcTemplate jdbc;
    private final AccountProfiles accounts;
    private final ContentService content;

    ScheduleService(JdbcTemplate jdbc, AccountProfiles accounts, ContentService content) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.content = content;
    }

    @Transactional
    public Week create(long ownerId, String accountId, LocalDate monday) {
        if (accountId == null || accounts.find(ownerId, accountId).isEmpty())
            throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        if (monday == null || monday.getDayOfWeek() != DayOfWeek.MONDAY)
            throw new ContentValidator.ContentInvalid("INVALID_WEEK_START");
        var existing = findByAccountAndStart(ownerId, accountId, monday);
        if (existing.isPresent()) return existing.get();
        var account = accounts.find(ownerId, accountId).orElseThrow();
        var weekId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO content_schedule_weeks (id, owner_id, account_id, week_start) VALUES (?, ?, ?, ?)",
                weekId, ownerId, accountId, monday);
        var columns = columns(account.columns(), account.weeklyTarget());
        for (int i = 0; i < columns.size(); i++) {
            var date = monday.plusDays(columns.size() == 1 ? 0 : Math.round(i * 6.0 / (columns.size() - 1)));
            jdbc.update("""
                    INSERT INTO content_schedule_items (id, week_id, position, column_name, scheduled_date)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), weekId, i, columns.get(i), date);
        }
        return find(ownerId, weekId).orElseThrow();
    }

    public Optional<Week> findByAccountAndStart(long ownerId, String accountId, LocalDate monday) {
        if (accountId == null || accounts.find(ownerId, accountId).isEmpty())
            throw new ContentValidator.ContentInvalid("ACCOUNT_NOT_FOUND");
        if (monday == null || monday.getDayOfWeek() != DayOfWeek.MONDAY)
            throw new ContentValidator.ContentInvalid("INVALID_WEEK_START");
        try {
            var id = jdbc.queryForObject("""
                    SELECT id FROM content_schedule_weeks WHERE owner_id = ? AND account_id = ? AND week_start = ?
                    """, String.class, ownerId, accountId, monday);
            return find(ownerId, id);
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    public Optional<Week> find(long ownerId, String weekId) {
        try {
            var week = jdbc.queryForObject("""
                    SELECT id, account_id, week_start FROM content_schedule_weeks WHERE owner_id = ? AND id = ?
                    """, (row, ignored) -> new Week(row.getString("id"), row.getString("account_id"),
                    row.getDate("week_start").toLocalDate(), List.of()), ownerId, weekId);
            var items = jdbc.query("""
                    SELECT id, column_name, scheduled_date, topic_id, script_id, version
                    FROM content_schedule_items WHERE week_id = ? ORDER BY position
                    """, this::mapItem, weekId);
            return Optional.of(new Week(week.id(), week.accountId(), week.weekStart(), items));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Transactional
    public Item move(long ownerId, String itemId, int expectedVersion, LocalDate date) {
        var week = jdbc.query("""
                SELECT w.week_start FROM content_schedule_items i
                JOIN content_schedule_weeks w ON w.id = i.week_id
                WHERE i.id = ? AND w.owner_id = ?
                """, (row, ignored) -> row.getDate("week_start").toLocalDate(), itemId, ownerId);
        if (week.isEmpty()) throw new ContentValidator.ContentInvalid("PLAN_ITEM_NOT_FOUND");
        if (date == null || date.isBefore(week.getFirst()) || date.isAfter(week.getFirst().plusDays(6)))
            throw new ContentValidator.ContentInvalid("INVALID_SCHEDULE_DATE");
        int changed = jdbc.update("""
                UPDATE content_schedule_items SET scheduled_date = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, date, itemId, expectedVersion);
        if (changed == 0) throw new ContentService.VersionConflict();
        return jdbc.queryForObject("""
                SELECT id, column_name, scheduled_date, topic_id, script_id, version
                FROM content_schedule_items WHERE id = ?
                """, this::mapItem, itemId);
    }

    @Transactional
    public Item changeColumn(long ownerId, String itemId, int expectedVersion, String column) {
        var matches = jdbc.query("""
                SELECT w.account_id, i.topic_id, i.script_id, i.version FROM content_schedule_items i
                JOIN content_schedule_weeks w ON w.id = i.week_id WHERE i.id = ? AND w.owner_id = ?
                """, (row, ignored) -> new ItemColumn(row.getString("account_id"), row.getString("topic_id"),
                row.getString("script_id"), row.getInt("version")), itemId, ownerId);
        if (matches.isEmpty()) throw new ContentValidator.ContentInvalid("PLAN_ITEM_NOT_FOUND");
        var current = matches.getFirst();
        if (current.version() != expectedVersion) throw new ContentService.VersionConflict();
        if (current.topicId() != null || current.scriptId() != null)
            throw new ContentValidator.ContentInvalid("PLAN_ITEM_OCCUPIED");
        if (column == null || !accounts.find(ownerId, current.accountId()).orElseThrow().columns().contains(column))
            throw new ContentValidator.ContentInvalid("INVALID_COLUMN");
        int changed = jdbc.update("""
                UPDATE content_schedule_items SET column_name = ?, version = version + 1
                WHERE id = ? AND version = ? AND topic_id IS NULL AND script_id IS NULL
                """, column, itemId, expectedVersion);
        if (changed == 0) throw new ContentService.VersionConflict();
        return jdbc.queryForObject("""
                SELECT id, column_name, scheduled_date, topic_id, script_id, version
                FROM content_schedule_items WHERE id = ?
                """, this::mapItem, itemId);
    }

    @Transactional
    public Week attachBatch(long ownerId, String weekId, String batchId) {
        var week = find(ownerId, weekId).orElseThrow(() -> new ContentValidator.ContentInvalid("PLAN_NOT_FOUND"));
        var batch = content.findWeekPlan(ownerId, batchId)
                .filter(found -> found.accountId().equals(week.accountId()))
                .orElseThrow(() -> new ContentValidator.ContentInvalid("WEEK_PLAN_NOT_FOUND"));
        if (week.items().size() != batch.items().size())
            throw new ContentValidator.ContentInvalid("INVALID_WEEK_PLAN");
        for (int i = 0; i < week.items().size(); i++) {
            var item = week.items().get(i);
            var draft = batch.items().get(i);
            if (!item.column().equals(draft.column()))
                throw new ContentValidator.ContentInvalid("INVALID_WEEK_PLAN");
            if (item.topicId() != null || item.scriptId() != null) {
                if (!draft.topicId().equals(item.topicId()) || !draft.scriptId().equals(item.scriptId()))
                    throw new ContentValidator.ContentInvalid("PLAN_ITEM_OCCUPIED");
            }
        }
        for (int i = 0; i < week.items().size(); i++) {
            var item = week.items().get(i);
            if (item.scriptId() != null) continue;
            var draft = batch.items().get(i);
            int changed = jdbc.update("""
                    UPDATE content_schedule_items SET topic_id = ?, script_id = ?, version = version + 1
                    WHERE id = ? AND version = ? AND topic_id IS NULL AND script_id IS NULL
                    """, draft.topicId(), draft.scriptId(), item.id(), item.version());
            if (changed == 0) throw new ContentService.VersionConflict();
        }
        return find(ownerId, weekId).orElseThrow();
    }

    public Optional<Slot> slot(long ownerId, String itemId) {
        return jdbc.query("""
                SELECT w.account_id, i.id, i.column_name, i.scheduled_date,
                       i.topic_id, i.script_id, i.version
                FROM content_schedule_items i JOIN content_schedule_weeks w ON w.id = i.week_id
                WHERE i.id = ? AND w.owner_id = ?
                """, (row, ignored) -> new Slot(row.getString("account_id"), mapItem(row, ignored)),
                itemId, ownerId).stream().findFirst();
    }

    @Transactional
    public Item replaceDraft(long ownerId, String itemId, int expectedVersion, String topicId, String scriptId) {
        var slots = jdbc.query("""
                SELECT w.account_id, i.id, i.column_name, i.scheduled_date,
                       i.topic_id, i.script_id, i.version
                FROM content_schedule_items i JOIN content_schedule_weeks w ON w.id = i.week_id
                WHERE i.id = ? AND w.owner_id = ? FOR UPDATE
                """, (row, ignored) -> new Slot(row.getString("account_id"), mapItem(row, ignored)),
                itemId, ownerId);
        if (slots.isEmpty()) throw new ContentValidator.ContentInvalid("PLAN_ITEM_NOT_FOUND");
        var slot = slots.getFirst();
        if (slot.item().version() != expectedVersion) throw new ContentService.VersionConflict();
        if (slot.item().scriptId() != null) {
            var status = jdbc.queryForObject("""
                    SELECT status FROM content_scripts WHERE id = ? AND owner_id = ? FOR UPDATE
                    """, String.class, slot.item().scriptId(), ownerId);
            if ("CONFIRMED".equals(status))
                throw new ContentValidator.ContentInvalid("SCRIPT_CONFIRMED");
        }
        var topic = content.findTopic(ownerId, topicId)
                .orElseThrow(() -> new ContentValidator.ContentInvalid("TOPIC_NOT_FOUND"));
        var script = content.findScript(ownerId, scriptId)
                .orElseThrow(() -> new ContentValidator.ContentInvalid("SCRIPT_NOT_FOUND"));
        if (!slot.accountId().equals(topic.accountId()) || !slot.item().column().equals(topic.topic().column())
                || !topicId.equals(script.topicId()))
            throw new ContentValidator.ContentInvalid("INVALID_WEEK_PLAN");
        int changed = jdbc.update("""
                UPDATE content_schedule_items SET topic_id = ?, script_id = ?, version = version + 1
                WHERE id = ? AND version = ?
                """, topicId, scriptId, itemId, expectedVersion);
        if (changed == 0) throw new ContentService.VersionConflict();
        return slot(ownerId, itemId).orElseThrow().item();
    }

    private Item mapItem(ResultSet row, int ignored) throws SQLException {
        return new Item(row.getString("id"), row.getString("column_name"),
                row.getDate("scheduled_date").toLocalDate(), row.getString("topic_id"),
                row.getString("script_id"), row.getInt("version"));
    }

    private record ItemColumn(String accountId, String topicId, String scriptId, int version) { }

    private List<String> columns(List<String> configured, int target) {
        var slots = new ArrayList<String>(target);
        if (configured.contains("Java 面试") && configured.contains("英语跟读")) {
            for (int i = 0; i < target - 1; i++) slots.add("Java 面试");
            slots.add("英语跟读");
        } else {
            for (int i = 0; i < target; i++) slots.add(configured.get(i % configured.size()));
        }
        return slots;
    }

    public record Week(String id, String accountId, LocalDate weekStart, List<Item> items) { }
    public record Slot(String accountId, Item item) { }
    public record Item(String id, String column, LocalDate scheduledDate, String topicId,
                       String scriptId, int version) { }
}
