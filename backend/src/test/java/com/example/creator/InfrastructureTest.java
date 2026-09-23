package com.example.creator;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class InfrastructureTest extends IntegrationTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RedissonClient redisson;

    @Test
    void migratedUsersCanBeStoredAndEmailMustBeUnique() {
        var email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)",
                email, "not-a-login-hash", "迁移测试");
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE email = ?", String.class, email))
                .isEqualTo("迁移测试");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?)",
                email.toUpperCase(), "not-a-login-hash", "重复邮箱"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void redissonCanReadWriteAndExpireCacheEntries() {
        var bucket = redisson.<String>getBucket("creator:test:" + UUID.randomUUID(), StringCodec.INSTANCE);
        try {
            bucket.set("连通检查", Duration.ofMinutes(1));
            assertThat(bucket.get()).isEqualTo("连通检查");
            assertThat(bucket.remainTimeToLive()).isBetween(1L, 60_000L);
        } finally {
            bucket.delete();
        }
    }
}
