/**
 * Legacy MQ Bridge — retired in Q? 2019 under MIG-9708.
 *
 * <p>Replaced by {@code MessagingBridge}; this package is retained
 * because deletion would break a long tail of internal
 * tooling that still resolves the FQN on the classpath
 * (audit-log indexer, change-management report exporter,
 * the legacy COBOL bridge that imports the entity types
 * via reflection). It is **not** wired into any Spring
 * context and carries no scheduled tasks.
 *
 * <p>If you find yourself reading this code looking for
 * authoritative behavior, stop — see {@code MessagingBridge}.
 */
@Deprecated(since = "2019-01-01", forRemoval = false)
package com.omnibank.legacy.integration.legacymqbridge;
