package com.omnibank.cards.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository contract for issued cards. Implementations are expected to be
 * idempotent on {@link #save(CardEntity)} — saving the same id overwrites
 * the previous version.
 */
public interface CardRepository {

    CardEntity save(CardEntity card);

    Optional<CardEntity> findById(UUID cardId);

    List<CardEntity> findByHolder(java.util.UUID holderId);

    List<CardEntity> findAll();

    void delete(UUID cardId);
}
