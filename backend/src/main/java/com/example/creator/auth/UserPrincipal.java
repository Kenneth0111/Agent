package com.example.creator.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class UserPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final long userId;

    UserPrincipal(UserAccount user) {
        super(user.getEmail(), user.getPasswordHash(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = user.getId();
    }

    public long getUserId() { return userId; }
}
