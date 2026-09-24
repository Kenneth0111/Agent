package com.example.creator.account;

import com.example.creator.agent.AccountProfile;
import com.example.creator.agent.AccountProfiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements AccountProfiles {
    private static final String FIELDS = "id, owner_id, name, audience, positioning, columns_json, weekly_target";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    AccountService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<AccountProfile> ownedBy(long ownerId) {
        return jdbc.query("SELECT " + FIELDS + " FROM content_accounts WHERE owner_id = ? ORDER BY created_at, id",
                this::map, ownerId);
    }

    @Override
    public Optional<AccountProfile> find(long ownerId, String accountId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT " + FIELDS
                    + " FROM content_accounts WHERE owner_id = ? AND id = ?", this::map, ownerId, accountId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Transactional
    public AccountProfile create(long ownerId, AccountInput input) {
        var clean = validate(input);
        var id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO content_accounts (id, owner_id, name, audience, positioning, columns_json, weekly_target)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)", id, ownerId, clean.name(), clean.audience(),
                clean.positioning(), columnsJson(clean.columns()), clean.weeklyTarget());
        return find(ownerId, id).orElseThrow();
    }

    @Transactional
    public Optional<AccountProfile> update(long ownerId, String accountId, AccountInput input) {
        var clean = validate(input);
        int updated = jdbc.update("UPDATE content_accounts SET name = ?, audience = ?, positioning = ?,"
                        + " columns_json = ?, weekly_target = ? WHERE owner_id = ? AND id = ?",
                clean.name(), clean.audience(), clean.positioning(), columnsJson(clean.columns()),
                clean.weeklyTarget(), ownerId, accountId);
        return updated == 0 ? Optional.empty() : find(ownerId, accountId);
    }

    private AccountInput validate(AccountInput input) {
        if (input == null || input.name() == null || input.audience() == null
                || input.positioning() == null || input.columns() == null) throw new IllegalArgumentException();
        var name = input.name().strip();
        var audience = input.audience().strip();
        var positioning = input.positioning().strip();
        var columns = input.columns().stream().map(value -> value == null ? "" : value.strip()).toList();
        if (name.isBlank() || name.length() > 80 || audience.isBlank() || audience.length() > 160
                || positioning.isBlank() || positioning.length() > 500 || columns.isEmpty() || columns.size() > 10
                || columns.stream().anyMatch(value -> value.isBlank() || value.length() > 80)
                || input.weeklyTarget() < 1 || input.weeklyTarget() > 21) throw new IllegalArgumentException();
        return new AccountInput(name, audience, positioning, columns, input.weeklyTarget());
    }

    private String columnsJson(List<String> columns) {
        try {
            return json.writeValueAsString(columns);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private AccountProfile map(ResultSet row, int index) throws SQLException {
        try {
            return new AccountProfile(row.getString("id"), row.getLong("owner_id"), row.getString("name"),
                    row.getString("audience"), row.getString("positioning"),
                    json.readValue(row.getString("columns_json"), new TypeReference<List<String>>() { }),
                    row.getInt("weekly_target"));
        } catch (JsonProcessingException invalid) {
            throw new SQLException("Invalid account columns", invalid);
        }
    }

    public record AccountInput(String name, String audience, String positioning,
                               List<String> columns, int weeklyTarget) { }
}
