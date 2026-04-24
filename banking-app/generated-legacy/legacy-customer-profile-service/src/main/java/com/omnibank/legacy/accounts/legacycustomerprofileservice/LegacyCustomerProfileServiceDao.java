package com.omnibank.legacy.accounts.legacycustomerprofileservice;

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
 * In-memory shadow DAO for the retired Legacy Customer Profile Service.
 *
 * <p>// DO NOT MODIFY — retired 2018 under MIG-2001 (replaced by ConsumerAccountService).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2018; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2018-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyCustomerProfileServiceDao {

    private static final Logger log = LoggerFactory.getLogger(LegacyCustomerProfileServiceDao.class);
    private final ConcurrentHashMap<UUID, LegacyCustomerProfileServiceEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code ConsumerAccountService}'s save method. */
    @Deprecated(since = "2018-01-01")
    public LegacyCustomerProfileServiceEntity save(LegacyCustomerProfileServiceEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code ConsumerAccountService}'s findById. */
    @Deprecated(since = "2018-01-01")
    public Optional<LegacyCustomerProfileServiceEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code ConsumerAccountService}'s findAll. */
    @Deprecated(since = "2018-01-01")
    public List<LegacyCustomerProfileServiceEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2018-01-01")
    public List<LegacyCustomerProfileServiceEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacyCustomerProfileServiceEntity>();
        for (var e : store.values()) {
            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /** @deprecated Counting is now done via the analytics service. */
    @Deprecated(since = "2018-01-01")
    public long count() { return store.size(); }

    /**
     * @deprecated Bulk-import shim retained for the FY2018 historical
     *             data migration job. Do not reuse.
     */
    @Deprecated(since = "2018-01-01")
    public void bulkInsert(Collection<LegacyCustomerProfileServiceEntity> entities) {
        for (var e : entities) save(e);
    }
}
