package com.omnibank.legacy.statements.legacyestatementsender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory shadow DAO for the retired Legacy E-Statement Sender.
 *
 * <p>// DO NOT MODIFY — retired 2020 under MIG-6008 (replaced by StatementDeliveryService).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2020; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2020-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyEStatementSenderDao {

    private static final Logger log = LoggerFactory.getLogger(LegacyEStatementSenderDao.class);
    private final ConcurrentHashMap<UUID, LegacyEStatementSenderEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code StatementDeliveryService}'s save method. */
    @Deprecated(since = "2020-01-01")
    public LegacyEStatementSenderEntity save(LegacyEStatementSenderEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code StatementDeliveryService}'s findById. */
    @Deprecated(since = "2020-01-01")
    public Optional<LegacyEStatementSenderEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code StatementDeliveryService}'s findAll. */
    @Deprecated(since = "2020-01-01")
    public List<LegacyEStatementSenderEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2020-01-01")
    public List<LegacyEStatementSenderEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacyEStatementSenderEntity>();
        for (var e : store.values()) {
            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /** @deprecated Counting is now done via the analytics service. */
    @Deprecated(since = "2020-01-01")
    public long count() { return store.size(); }

    /**
     * @deprecated Bulk-import shim retained for the FY2020 historical
     *             data migration job. Do not reuse.
     */
    @Deprecated(since = "2020-01-01")
    public void bulkInsert(Collection<LegacyEStatementSenderEntity> entities) {
        for (var e : entities) save(e);
    }
}
