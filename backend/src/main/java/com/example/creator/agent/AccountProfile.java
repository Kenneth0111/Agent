package com.example.creator.agent;

import java.util.List;

public record AccountProfile(String id, long ownerId, String name, String positioning,
                             List<String> columns, int weeklyTarget) { }
