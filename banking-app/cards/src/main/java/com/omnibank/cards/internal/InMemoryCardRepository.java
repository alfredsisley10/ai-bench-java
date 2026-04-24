package com.omnibank.cards.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/**
 * In-memory repository used by unit tests and local development. Production
 * wiring swaps in a JPA-backed implementation; this one keeps a
 * ConcurrentHashMap so tests can exercise the service classes end-to-end
 * without Spring context.
 */
@Repository
public class InMemoryCardRepository implements CardRepository {

    private final Map<UUID, CardEntity> store = new ConcurrentHashMap<>();

    @Override
    public CardEntity save(CardEntity card) {
        store.put(card.cardId(), card);
        return card;
    }

    @Override
    public Optional<CardEntity> findById(UUID cardId) {
        return Optional.ofNullable(store.get(cardId));
    }

    @Override
    public List<CardEntity> findByHolder(UUID holderId) {
        List<CardEntity> out = new ArrayList<>();
        for (var card : store.values()) {
            if (card.holder().value().equals(holderId)) {
                out.add(card);
            }
        }
        return out;
    }

    @Override
    public List<CardEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void delete(UUID cardId) {
        store.remove(cardId);
    }

    public int size() {
        return store.size();
    }
}
