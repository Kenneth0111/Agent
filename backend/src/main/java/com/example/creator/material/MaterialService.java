package com.example.creator.material;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService {
    private final JdbcTemplate jdbc;
    private final TextExtractor extractor;

    MaterialService(JdbcTemplate jdbc, TextExtractor extractor) {
        this.jdbc = jdbc;
        this.extractor = extractor;
    }

    public List<MaterialSummary> ownedBy(long ownerId) {
        return jdbc.query("""
                SELECT id, title, purpose, source_url, file_name, segment_count
                FROM materials WHERE owner_id = ? ORDER BY created_at DESC, id DESC
                """, this::summary, ownerId);
    }

    public Optional<MaterialDetail> find(long ownerId, String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, title, purpose, source_url, file_name, segment_count, content
                    FROM materials WHERE owner_id = ? AND id = ?
                    """, this::detail, ownerId, id));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Transactional
    public MaterialDetail create(long ownerId, MaterialInput input) {
        if (input == null) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        var title = required(input.title(), 200);
        var purpose = required(input.purpose(), 80);
        var sourceUrl = sourceUrl(input.sourceUrl());
        var fileName = fileName(input.fileName());
        var extracted = extractor.extract(input.content());
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO materials (id, owner_id, title, purpose, source_url, file_name, content, segment_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerId, title, purpose, sourceUrl, fileName,
                extracted.content(), extracted.segments().size());
        for (int index = 0; index < extracted.segments().size(); index++) {
            jdbc.update("INSERT INTO material_segments (material_id, segment_index, body) VALUES (?, ?, ?)",
                    id, index, extracted.segments().get(index));
        }
        return find(ownerId, id).orElseThrow();
    }

    @Transactional
    public boolean delete(long ownerId, String id) {
        return jdbc.update("DELETE FROM materials WHERE owner_id = ? AND id = ?", ownerId, id) == 1;
    }

    private String required(String value, int maxLength) {
        if (value == null || value.isBlank()) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        var clean = value.strip();
        if (clean.length() > maxLength) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        return clean;
    }

    private String sourceUrl(String value) {
        if (value == null || value.isBlank()) return null;
        var clean = value.strip();
        if (clean.length() > 2048) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        try {
            var uri = new URI(clean);
            if (!List.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
            }
            return clean;
        } catch (URISyntaxException invalid) {
            throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        }
    }

    private String fileName(String value) {
        if (value == null || value.isBlank()) return null;
        var clean = value.strip();
        var lower = clean.toLowerCase(Locale.ROOT);
        if (clean.length() > 255 || clean.contains("/") || clean.contains("\\")
                || !(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown"))) {
            throw new TextExtractor.MaterialInvalid("UNSUPPORTED_FILE");
        }
        return clean;
    }

    private MaterialSummary summary(ResultSet row, int index) throws SQLException {
        return new MaterialSummary(row.getString("id"), row.getString("title"), row.getString("purpose"),
                row.getString("source_url"), row.getString("file_name"), row.getInt("segment_count"));
    }

    private MaterialDetail detail(ResultSet row, int index) throws SQLException {
        return new MaterialDetail(row.getString("id"), row.getString("title"), row.getString("purpose"),
                row.getString("source_url"), row.getString("file_name"), row.getInt("segment_count"),
                row.getString("content"));
    }

    public record MaterialInput(String title, String purpose, String sourceUrl, String fileName, String content) { }
    public record MaterialSummary(String id, String title, String purpose, String sourceUrl,
                                  String fileName, int segmentCount) { }
    public record MaterialDetail(String id, String title, String purpose, String sourceUrl,
                                 String fileName, int segmentCount, String content) { }
}
