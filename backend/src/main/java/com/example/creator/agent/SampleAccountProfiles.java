package com.example.creator.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test fixture for workflow tests; application accounts are stored in MySQL. */
public class SampleAccountProfiles implements AccountProfiles {
    private final Map<Long, List<AccountProfile>> byOwner = new ConcurrentHashMap<>();

    @Override
    public List<AccountProfile> ownedBy(long ownerId) {
        return byOwner.computeIfAbsent(ownerId, SampleAccountProfiles::samplesFor);
    }

    private static List<AccountProfile> samplesFor(long ownerId) {
        return List.of(
                new AccountProfile("sample-" + ownerId + "-java", ownerId, "Java 面试快问快答",
                        "面向 1 到 3 年经验的 Java 求职者，讲清高频面试题",
                        List.of("Java 基础", "并发", "JVM"), 2),
                new AccountProfile("sample-" + ownerId + "-toefl", ownerId, "托福英语跟读",
                        "记录本人备考过程，带观众一起跟读短材料",
                        List.of("跟读材料", "表达解释"), 1));
    }
}
