package com.example.creator.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwords;

    InviteService(JdbcTemplate jdbc, UserRepository users, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserAccount register(Registration registration) {
        var email = normalizeEmail(registration.email());
        var displayName = registration.displayName() == null ? "" : registration.displayName().strip();
        var code = registration.invitationCode() == null ? "" : registration.invitationCode().strip();
        if (!isEmail(email) || displayName.isBlank() || displayName.length() > 80
                || code.length() < 8 || code.length() > 128 || registration.password() == null
                || registration.password().length() < 12 || registration.password().length() > 200
                || users.findByEmail(email).isPresent()) {
            throw new RegistrationRejected();
        }
        var consumed = jdbc.update("""
                UPDATE invitations
                SET used_at = UTC_TIMESTAMP(6)
                WHERE code_hash = ? AND used_at IS NULL AND expires_at > UTC_TIMESTAMP(6)
                """, hash(code));
        if (consumed != 1) {
            throw new RegistrationRejected();
        }
        return users.saveAndFlush(new UserAccount(email, passwords.encode(registration.password()), displayName));
    }

    @Transactional
    void createDevInvitationIfAbsent(String code, Duration expiresIn) {
        if (code == null || code.isBlank()) {
            return;
        }
        jdbc.update("""
                INSERT IGNORE INTO invitations (code_hash, expires_at)
                VALUES (?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? SECOND))
                """, hash(code.strip()), expiresIn.toSeconds());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean isEmail(String email) {
        return email.length() <= 254 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Registration(String invitationCode, String email, String displayName, String password) { }

    public static final class RegistrationRejected extends RuntimeException { }
}
