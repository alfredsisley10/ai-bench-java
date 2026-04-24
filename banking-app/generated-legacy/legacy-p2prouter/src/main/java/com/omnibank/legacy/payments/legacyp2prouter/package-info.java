/**
 * Legacy P2P Router — retired in Q? 2021 under MIG-1090.
 *
 * <p>Replaced by {@code P2PPaymentProcessor}; this package is retained
 * because deletion would break a long tail of internal
 * tooling that still resolves the FQN on the classpath
 * (audit-log indexer, change-management report exporter,
 * the legacy COBOL bridge that imports the entity types
 * via reflection). It is **not** wired into any Spring
 * context and carries no scheduled tasks.
 *
 * <p>If you find yourself reading this code looking for
 * authoritative behavior, stop — see {@code P2PPaymentProcessor}.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
package com.omnibank.legacy.payments.legacyp2prouter;
