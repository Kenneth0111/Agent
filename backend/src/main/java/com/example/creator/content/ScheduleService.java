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

    ScheduleService(JdbcTemplate jdbc, AccountProfiles accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
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

    private Item mapItem(ResultSet row, int ignored) throws SQLException {
        return new Item(row.getString("id"), row.getString("column_name"),
                row.getDate("scheduled_date").toLocalDate(), row.getString("topic_id"),
                row.getString("script_id"), row.getInt("version"));
    }

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
    public record Item(String id, String column, LocalDate scheduledDate, String topicId,
                       String scriptId, int version) { }
}
