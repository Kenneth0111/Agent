package com.example.creator.material;

import com.example.creator.agent.AccountProfiles;
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
    private final PdfTextExtractor pdfExtractor;
    private final AccountProfiles accounts;

    MaterialService(JdbcTemplate jdbc, TextExtractor extractor, PdfTextExtractor pdfExtractor, AccountProfiles accounts) {
        this.jdbc = jdbc;
        this.extractor = extractor;
        this.pdfExtractor = pdfExtractor;
        this.accounts = accounts;
    }

    public List<MaterialSummary> ownedBy(long ownerId) {
        return jdbc.query("""
                SELECT id, title, purpose, source_url, file_name, segment_count, kind
                FROM materials WHERE owner_id = ? ORDER BY created_at DESC, id DESC
                """, this::summary, ownerId);
    }

    public Optional<MaterialDetail> find(long ownerId, String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, title, purpose, source_url, file_name, segment_count, content, kind
                    FROM materials WHERE owner_id = ? AND id = ?
                    """, this::detail, ownerId, id));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Transactional
    public MaterialDetail create(long ownerId, MaterialInput input) {
        if (input == null) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        var kind = input.kind() == null ? "TEXT" : input.kind();
        if (!List.of("TEXT", "LINK").contains(kind)) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        return save(ownerId, input, kind);
    }

    @Transactional
    public MaterialDetail createPdf(long ownerId, MaterialInput input, org.springframework.web.multipart.MultipartFile file) {
        if (input == null) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        var content = pdfExtractor.extract(file);
        var fileName = file.getOriginalFilename();
        if (fileName.length() > 255 || fileName.contains("/") || fileName.contains("\\"))
            throw new TextExtractor.MaterialInvalid("INVALID_PDF");
        return save(ownerId, new MaterialInput(input.title(), input.purpose(), input.sourceUrl(),
                fileName, content, "PDF", input.accountIds()), "PDF");
    }

    private MaterialDetail save(long ownerId, MaterialInput input, String kind) {
        var title = required(input.title(), 200);
        var purpose = required(input.purpose(), 80);
        var sourceUrl = sourceUrl(input.sourceUrl());
        if ("LINK".equals(kind) && sourceUrl == null) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        var fileName = "PDF".equals(kind) ? input.fileName() : fileName(input.fileName());
        if ("LINK".equals(kind) && fileName != null) throw new TextExtractor.MaterialInvalid("INVALID_MATERIAL");
        var accountIds = input.accountIds() == null ? List.<String>of() : input.accountIds().stream().distinct().toList();
        for (var accountId : accountIds) {
            if (accountId == null || accounts.find(ownerId, accountId).isEmpty())
                throw new TextExtractor.MaterialInvalid("ACCOUNT_NOT_FOUND");
        }
        var extracted = extractor.extract(input.content());
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO materials (id, owner_id, title, purpose, source_url, file_name, content, segment_count, kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerId, title, purpose, sourceUrl, fileName,
                extracted.content(), extracted.segments().size(), kind);
        for (int index = 0; index < extracted.segments().size(); index++) {
            jdbc.update("INSERT INTO material_segments (material_id, segment_index, body) VALUES (?, ?, ?)",
                    id, index, extracted.segments().get(index));
        }
        for (var accountId : accountIds) {
            jdbc.update("INSERT INTO material_accounts (material_id, account_id) VALUES (?, ?)", id, accountId);
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
                row.getString("source_url"), row.getString("file_name"), row.getInt("segment_count"),
                row.getString("kind"), accountIds(row.getString("id")));
    }

    private MaterialDetail detail(ResultSet row, int index) throws SQLException {
        return new MaterialDetail(row.getString("id"), row.getString("title"), row.getString("purpose"),
                row.getString("source_url"), row.getString("file_name"), row.getInt("segment_count"),
                row.getString("content"), row.getString("kind"), accountIds(row.getString("id")));
    }

    private List<String> accountIds(String materialId) {
        return jdbc.queryForList("SELECT account_id FROM material_accounts WHERE material_id = ? ORDER BY account_id",
                String.class, materialId);
    }

    public record MaterialInput(String title, String purpose, String sourceUrl, String fileName,
                                String content, String kind, List<String> accountIds) { }
    public record MaterialSummary(String id, String title, String purpose, String sourceUrl,
                                  String fileName, int segmentCount, String kind, List<String> accountIds) { }
    public record MaterialDetail(String id, String title, String purpose, String sourceUrl,
                                 String fileName, int segmentCount, String content, String kind,
                                 List<String> accountIds) implements java.io.Serializable { }
}
