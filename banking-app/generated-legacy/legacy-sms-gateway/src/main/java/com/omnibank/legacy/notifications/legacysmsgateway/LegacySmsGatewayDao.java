package com.omnibank.legacy.notifications.legacysmsgateway;

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
 * In-memory shadow DAO for the retired Legacy SMS Gateway.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-9022 (replaced by SmsDeliveryService).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2021; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacySmsGatewayDao {

    private static final Logger log = LoggerFactory.getLogger(LegacySmsGatewayDao.class);
    private final ConcurrentHashMap<UUID, LegacySmsGatewayEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code SmsDeliveryService}'s save method. */
    @Deprecated(since = "2021-01-01")
    public LegacySmsGatewayEntity save(LegacySmsGatewayEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code SmsDeliveryService}'s findById. */
    @Deprecated(since = "2021-01-01")
    public Optional<LegacySmsGatewayEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code SmsDeliveryService}'s findAll. */
    @Deprecated(since = "2021-01-01")
    public List<LegacySmsGatewayEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2021-01-01")
    public List<LegacySmsGatewayEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacySmsGatewayEntity>();
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
    public void bulkInsert(Collection<LegacySmsGatewayEntity> entities) {
        for (var e : entities) save(e);
    }
}
