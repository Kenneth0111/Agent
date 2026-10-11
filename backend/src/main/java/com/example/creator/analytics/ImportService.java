package com.example.creator.analytics;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportService {
    static final int MAX_BYTES = 1_048_576;
    private static final List<String> HEADER = List.of("accountId", "videoId", "capturedAt",
            "views", "likes", "commentCount", "source");
    private final JdbcTemplate jdbc;

    ImportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Preview preview(long ownerId, byte[] csv) {
        return assess(ownerId, parse(csv));
    }

    private Preview assess(long ownerId, List<RowResult> parsed) {
        var accounts = new HashMap<String, Boolean>();
        var seen = new HashMap<SnapshotKey, MetricRow>();
        var rows = parsed.stream().map(row -> {
            if (row.data() == null) return row;
            var data = row.data();
            boolean owned = accounts.computeIfAbsent(data.accountId(), account ->
                    !jdbc.queryForList("SELECT id FROM content_accounts WHERE owner_id = ? AND id = ?",
                            String.class, ownerId, account).isEmpty());
            if (!owned) return error(row.rowNumber(), "ACCOUNT_NOT_FOUND");
            var key = new SnapshotKey(data.accountId(), data.videoId(), data.capturedAt(), data.source());
            var earlier = seen.get(key);
            if (earlier != null) return sameCounts(earlier, data)
                    ? new RowResult(row.rowNumber(), "DUPLICATE", null, data)
                    : error(row.rowNumber(), "SNAPSHOT_CONFLICT");
            var existing = jdbc.query("""
                    SELECT s.views, s.likes, s.comment_count FROM video_metric_snapshots s
                    JOIN video_works w ON w.id = s.work_id AND w.owner_id = s.owner_id
                    WHERE w.owner_id = ? AND w.account_id = ? AND w.video_id = ?
                        AND s.captured_at = ? AND s.source = ?
                    """, (result, ignored) -> new MetricRow(data.rowNumber(), data.accountId(), data.videoId(),
                    data.capturedAt(), (Long) result.getObject("views"), (Long) result.getObject("likes"),
                    (Long) result.getObject("comment_count"), data.source()), ownerId, data.accountId(),
                    data.videoId(), utc(data.capturedAt()), data.source());
            if (!existing.isEmpty() && !sameCounts(existing.getFirst(), data))
                return error(row.rowNumber(), "SNAPSHOT_CONFLICT");
            seen.put(key, data);
            return existing.isEmpty() ? row : new RowResult(row.rowNumber(), "DUPLICATE", null, data);
        }).toList();
        return new Preview(rows.stream().noneMatch(row -> "ERROR".equals(row.status())), rows);
    }

    @Transactional
    public ImportResult confirm(long ownerId, byte[] csv) {
        var parsed = parse(csv);
        // A consistent lock order serializes concurrent imports for the same accounts.
        parsed.stream().filter(row -> row.data() != null).map(row -> row.data().accountId())
                .distinct().sorted().forEach(account -> jdbc.queryForList(
                        "SELECT id FROM content_accounts WHERE owner_id = ? AND id = ? FOR UPDATE",
                        String.class, ownerId, account));
        var preview = assess(ownerId, parsed);
        if (!preview.canImport()) throw new ImportRejected(preview);
        var results = new ArrayList<RowResult>();
        int imported = 0;
        for (var row : preview.rows()) {
            if ("DUPLICATE".equals(row.status())) { results.add(row); continue; }
            var data = row.data();
            var works = jdbc.queryForList("SELECT id FROM video_works WHERE owner_id = ? AND account_id = ? AND video_id = ?",
                    String.class, ownerId, data.accountId(), data.videoId());
            String workId;
            if (works.isEmpty()) {
                workId = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO video_works (id, owner_id, account_id, video_id) VALUES (?, ?, ?, ?)",
                        workId, ownerId, data.accountId(), data.videoId());
            } else workId = works.getFirst();
            jdbc.update("""
                    INSERT INTO video_metric_snapshots (id, owner_id, work_id, captured_at, views, likes, comment_count, source)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), ownerId, workId, utc(data.capturedAt()),
                    data.views(), data.likes(), data.commentCount(), data.source());
            results.add(new RowResult(row.rowNumber(), "IMPORTED", null, data));
            imported++;
        }
        return new ImportResult(imported, results.size() - imported, List.copyOf(results));
    }

    private List<RowResult> parse(byte[] csv) {
        if (csv == null || csv.length == 0) return List.of(error(1, "CSV_EMPTY"));
        if (csv.length > MAX_BYTES) return List.of(error(1, "CSV_TOO_LARGE"));
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(csv)).toString(); }
        catch (CharacterCodingException invalid) { return List.of(error(1, "CSV_UTF8_INVALID")); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        try (var parser = CSVFormat.RFC4180.parse(new StringReader(text))) {
            var records = parser.getRecords();
            if (records.isEmpty() || !records.getFirst().toList().equals(HEADER))
                return List.of(error(1, "CSV_HEADER_INVALID"));
            if (records.size() == 1) return List.of(error(2, "CSV_NO_ROWS"));
            if (records.size() > 1001) return List.of(error(1002, "CSV_TOO_MANY_ROWS"));
            var rows = new ArrayList<RowResult>();
            for (var record : records.subList(1, records.size())) {
                int number = Math.toIntExact(record.getRecordNumber());
                try {
                    if (record.size() != HEADER.size()) throw new RowInvalid("CSV_COLUMNS_INVALID");
                    var account = UUID.fromString(record.get(0).strip());
                    if (!account.toString().equalsIgnoreCase(record.get(0).strip()))
                        throw new RowInvalid("ACCOUNT_ID_INVALID");
                    var views = count(record.get(3), "VIEWS_INVALID");
                    var likes = count(record.get(4), "LIKES_INVALID");
                    var comments = count(record.get(5), "COMMENT_COUNT_INVALID");
                    if (views == null && likes == null && comments == null)
                        throw new RowInvalid("METRICS_MISSING");
                    var data = new MetricRow(number, account.toString(), value(record, 1, 128, "VIDEO_ID_INVALID"),
                            time(record.get(2)), views, likes, comments, value(record, 6, 160, "SOURCE_INVALID"));
                    rows.add(new RowResult(number, "READY", null, data));
                } catch (RowInvalid invalid) { rows.add(error(number, invalid.getMessage())); }
                catch (IllegalArgumentException invalid) { rows.add(error(number, "ACCOUNT_ID_INVALID")); }
            }
            return rows;
        } catch (IOException | UncheckedIOException invalid) { return List.of(error(1, "CSV_INVALID")); }
    }

    private static String value(CSVRecord record, int index, int max, String code) {
        var value = record.get(index).strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > max) throw new RowInvalid(code);
        return value;
    }

    private static Long count(String input, String code) {
        var value = input.strip();
        if (value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]+")) throw new RowInvalid(code);
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) { throw new RowInvalid(code); }
    }

    private static Instant time(String input) {
        try {
            if (input.matches(".*\\.\\d{7,}.*")) throw new RowInvalid("CAPTURED_AT_INVALID");
            var instant = OffsetDateTime.parse(input.strip()).toInstant();
            int year = LocalDateTime.ofInstant(instant, ZoneOffset.UTC).getYear();
            if (year < 1000 || year > 9999) throw new RowInvalid("CAPTURED_AT_INVALID");
            return instant;
        } catch (DateTimeException invalid) { throw new RowInvalid("CAPTURED_AT_INVALID"); }
    }

    private static RowResult error(int row, String code) { return new RowResult(row, "ERROR", code, null); }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static boolean sameCounts(MetricRow a, MetricRow b) {
        return Objects.equals(a.views(), b.views()) && Objects.equals(a.likes(), b.likes())
                && Objects.equals(a.commentCount(), b.commentCount());
    }

    public record MetricRow(int rowNumber, String accountId, String videoId, Instant capturedAt,
                            Long views, Long likes, Long commentCount, String source) { }
    public record RowResult(int rowNumber, String status, String errorCode, MetricRow data) { }
    public record Preview(boolean canImport, List<RowResult> rows) { }
    public record ImportResult(int imported, int duplicates, List<RowResult> rows) { }
    private record SnapshotKey(String accountId, String videoId, Instant capturedAt, String source) { }
    public static final class ImportRejected extends RuntimeException {
        private final Preview preview;
        ImportRejected(Preview preview) {
            super("CSV_VALIDATION_FAILED", null, false, false);
            this.preview = preview;
        }
        public Preview preview() { return preview; }
    }
    private static final class RowInvalid extends RuntimeException {
        RowInvalid(String code) { super(code, null, false, false); }
    }
}
