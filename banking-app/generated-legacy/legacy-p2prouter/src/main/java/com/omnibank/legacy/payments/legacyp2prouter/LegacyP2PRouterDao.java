package com.omnibank.legacy.payments.legacyp2prouter;

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
 * In-memory shadow DAO for the retired Legacy P2P Router.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-1090 (replaced by P2PPaymentProcessor).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2021; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyP2PRouterDao {

    private static final Logger log = LoggerFactory.getLogger(LegacyP2PRouterDao.class);
    private final ConcurrentHashMap<UUID, LegacyP2PRouterEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code P2PPaymentProcessor}'s save method. */
    @Deprecated(since = "2021-01-01")
    public LegacyP2PRouterEntity save(LegacyP2PRouterEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code P2PPaymentProcessor}'s findById. */
    @Deprecated(since = "2021-01-01")
    public Optional<LegacyP2PRouterEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code P2PPaymentProcessor}'s findAll. */
    @Deprecated(since = "2021-01-01")
    public List<LegacyP2PRouterEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2021-01-01")
    public List<LegacyP2PRouterEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacyP2PRouterEntity>();
        for (var e : store.values()) {
            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /** @deprecated Counting is now done via the analytics service. */
    @Deprecated(since = "2021-01-01")
    public long count() { return store.size(); }

    /**
     * @deprecated Bulk-import shim retained for the FY2021 historical
     *             data migration job. Do not reuse.
     */
    @Deprecated(since = "2021-01-01")
    public void bulkInsert(Collection<LegacyP2PRouterEntity> entities) {
        for (var e : entities) save(e);
    }
}
