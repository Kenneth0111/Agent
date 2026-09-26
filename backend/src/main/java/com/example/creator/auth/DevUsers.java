package com.example.creator.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
class DevUsers implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String password;

    DevUsers(UserRepository users, PasswordEncoder encoder,
             @Value("${DEV_USER_PASSWORD}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password.length() < 16) {
            throw new IllegalStateException("DEV_USER_PASSWORD must contain at least 16 characters");
        }
        createIfAbsent("creator-a@example.test", "开发用户 A");
        createIfAbsent("creator-b@example.test", "开发用户 B");
    }

    private void createIfAbsent(String email, String name) {
        if (users.findByEmail(email).isEmpty()) {
            users.save(new UserAccount(email, encoder.encode(password), name));
        }
    }
}
