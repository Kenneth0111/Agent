package com.example.creator.material;

import com.example.creator.agent.AccountProfiles;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MaterialSearchService {
    private final JdbcTemplate jdbc;
    private final AccountProfiles accounts;

    MaterialSearchService(JdbcTemplate jdbc, AccountProfiles accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public SearchResponse search(long ownerId, String accountId, String query) {
        if (accountId == null || accountId.isBlank() || accounts.find(ownerId, accountId.strip()).isEmpty())
            throw new SearchFailure("ACCOUNT_NOT_FOUND");
        if (query == null || query.isBlank() || query.strip().length() > 100)
            throw new SearchFailure("INVALID_QUERY");
        var term = "%" + query.strip().replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        var results = jdbc.query("""
                SELECT m.id, m.title, m.source_url, m.file_name, m.kind,
                    COALESCE(
                        (SELECT s.body FROM material_segments s WHERE s.material_id = m.id
                         AND s.body LIKE ? ESCAPE '!' ORDER BY s.segment_index LIMIT 1),
                        (SELECT s.body FROM material_segments s WHERE s.material_id = m.id
                         ORDER BY s.segment_index LIMIT 1)) AS snippet
                FROM materials m
                WHERE m.owner_id = ?
                  AND (NOT EXISTS (SELECT 1 FROM material_accounts ma WHERE ma.material_id = m.id)
                       OR EXISTS (SELECT 1 FROM material_accounts ma WHERE ma.material_id = m.id
                                  AND ma.account_id = ?))
                  AND (m.title LIKE ? ESCAPE '!'
                       OR EXISTS (SELECT 1 FROM material_segments s WHERE s.material_id = m.id
                                  AND s.body LIKE ? ESCAPE '!'))
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT 10
                """, this::result, term, ownerId, accountId.strip(), term, term);
        return new SearchResponse(results.isEmpty() ? "INSUFFICIENT_MATERIAL" : "MATCHED", results);
    }

    private SearchResult result(ResultSet row, int index) throws SQLException {
        return new SearchResult(row.getString("id"), row.getString("title"), row.getString("snippet"),
                row.getString("source_url"), row.getString("file_name"), row.getString("kind"));
    }

    public record SearchResponse(String status, List<SearchResult> results) { }
    public record SearchResult(String materialId, String title, String snippet,
                               String sourceUrl, String fileName, String kind) { }
    public static final class SearchFailure extends RuntimeException {
        SearchFailure(String code) { super(code); }
    }
}
