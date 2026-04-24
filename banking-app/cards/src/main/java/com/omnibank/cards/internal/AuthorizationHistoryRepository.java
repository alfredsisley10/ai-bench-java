package com.omnibank.cards.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/**
 * Thread-safe repository for authorization history. Used by velocity,
 * geographic anomaly, and dispute lookup paths.
 */
@Repository
public class AuthorizationHistoryRepository {

    private final Map<UUID, List<AuthorizationRecord>> byCard = new ConcurrentHashMap<>();

    public synchronized void record(AuthorizationRecord record) {
        byCard.computeIfAbsent(record.cardId(), k -> new ArrayList<>()).add(record);
    }

    public List<AuthorizationRecord> recentForCard(UUID cardId, Instant since) {
        var list = byCard.getOrDefault(cardId, Collections.emptyList());
        List<AuthorizationRecord> out = new ArrayList<>();
        for (var r : list) {
            if (!r.decidedAt().isBefore(since)) {
                out.add(r);
            }
        }
        return out;
    }

    public List<AuthorizationRecord> allForCard(UUID cardId) {
        return List.copyOf(byCard.getOrDefault(cardId, Collections.emptyList()));
    }

    public int size() {
        int total = 0;
        for (var list : byCard.values()) total += list.size();
        return total;
    }
}
