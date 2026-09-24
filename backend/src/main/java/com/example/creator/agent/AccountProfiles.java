package com.example.creator.agent;

import java.util.List;
import java.util.Optional;

/** Lookups are always scoped by owner: a caller can only reach its own accounts. */
public interface AccountProfiles {
    List<AccountProfile> ownedBy(long ownerId);

    default Optional<AccountProfile> find(long ownerId, String accountId) {
        return ownedBy(ownerId).stream().filter(profile -> profile.id().equals(accountId)).findFirst();
    }
}
