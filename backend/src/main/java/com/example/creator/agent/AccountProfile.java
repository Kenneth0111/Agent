package com.example.creator.agent;

import java.util.List;

public record AccountProfile(String id, long ownerId, String name, String audience, String positioning,
                             List<String> columns, int weeklyTarget) {
    public AccountProfile(String id, long ownerId, String name, String positioning,
                          List<String> columns, int weeklyTarget) {
        this(id, ownerId, name, "", positioning, columns, weeklyTarget);
    }
}
