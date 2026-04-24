package com.omnibank.audit.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Document-oriented persistence abstraction for audit events.
 *
 * <p>Modelled after MongoDB's document semantics: documents are schemaless
 * JSON blobs identified by a UUID, stored in logical collections, and
 * queryable via a rich filter API (see {@link AuditQueryEngine}).
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>Durability — a {@link #store} call that returns successfully must
 *       survive a process crash.</li>
 *   <li>Idempotency — re-storing a document with an existing {@code id}
 *       replaces the previous version (upsert semantics).</li>
 *   <li>Ordering — documents returned by range queries are ordered by
 *       {@code timestamp} descending (most-recent-first).</li>
 * </ul>
 */
public interface AuditEventStore {

    // -------------------------------------------------------------------
    //  Collection / index management
    // -------------------------------------------------------------------

    /**
     * Logical collection names. Each maps to a separate MongoDB collection
     * or Elasticsearch index.
     */
    enum CollectionName {
        /** High-volume transactional audit trail. */
        TRANSACTIONS,
        /** Authentication, authorisation, and session events. */
        SECURITY,
        /** Administrative and configuration change events. */
        ADMIN,
        /** Compliance and regulatory reporting events. */
        COMPLIANCE
    }

    /**
     * Index definition for the store.
     *
     * @param name   human-readable index name
     * @param fields ordered list of field paths to index
     * @param unique whether the index enforces uniqueness
     * @param sparse whether to skip documents missing the indexed fields
     */
    record IndexDefinition(
            String name,
            List<String> fields,
            boolean unique,
            boolean sparse
    ) {}

    /**
     * Ensures that the given collection exists and the required indexes are
     * in place. Idempotent.
     */
    void ensureCollection(CollectionName collection, List<IndexDefinition> indexes);

    // -------------------------------------------------------------------
    //  Write operations
    // -------------------------------------------------------------------

    /**
     * Stores a single audit document (upsert by {@code id}).
     *
     * @param collection target collection
     * @param document   the document to persist
     */
    void store(CollectionName collection, AuditDocument document);

    /**
     * Asynchronous variant of {@link #store}.
     */
    CompletableFuture<Void> storeAsync(CollectionName collection, AuditDocument document);

    /**
     * Bulk-inserts a batch of documents. Implementations should use the
     * database's native bulk API for efficiency.
     *
     * @return the number of documents successfully stored
     */
    int storeBatch(CollectionName collection, Collection<AuditDocument> documents);

    // -------------------------------------------------------------------
    //  Read operations
    // -------------------------------------------------------------------

    /**
     * Retrieves a document by its unique identifier.
     */
    Optional<AuditDocument> findById(CollectionName collection, UUID id);

    /**
     * Time-range query returning documents whose timestamp falls within
     * [{@code from}, {@code to}).
     *
     * @param from   inclusive lower bound
     * @param to     exclusive upper bound
     * @param limit  maximum number of documents to return
     * @return documents ordered by timestamp descending
     */
    List<AuditDocument> findByTimeRange(CollectionName collection,
                                        Instant from,
                                        Instant to,
                                        int limit);

    /**
     * Finds documents by the actor's principal ID.
     */
    List<AuditDocument> findByActor(CollectionName collection,
                                    String principalId,
                                    Instant from,
                                    Instant to,
                                    int limit);

    /**
     * Finds documents by resource type and resource ID.
     */
    List<AuditDocument> findByResource(CollectionName collection,
                                       String resourceType,
                                       String resourceId,
                                       int limit);

    /**
     * Full-text search across the indexed searchable fields.
     *
     * @param queryText free-form search text
     * @param limit     maximum results
     * @return matching documents ranked by relevance
     */
    List<AuditDocument> fullTextSearch(CollectionName collection,
                                       String queryText,
                                       int limit);

    // -------------------------------------------------------------------
    //  Cursor-based pagination
    // -------------------------------------------------------------------

    /**
     * Opaque cursor for paginated iteration through large result sets.
     * Implementations may encode a MongoDB cursor ID, an Elasticsearch
     * scroll token, or a simple offset.
     *
     * @param token      opaque pagination token
     * @param hasMore    whether more pages exist
     * @param totalCount estimated total matching documents (-1 if unknown)
     */
    record PageCursor(String token, boolean hasMore, long totalCount) {}

    /**
     * Paginated result set.
     *
     * @param documents the current page of results
     * @param cursor    cursor for fetching the next page
     */
    record Page(List<AuditDocument> documents, PageCursor cursor) {}

    /**
     * Fetches the first page of results for a time-range query.
     *
     * @param pageSize number of documents per page
     */
    Page findByTimeRangePaginated(CollectionName collection,
                                  Instant from,
                                  Instant to,
                                  int pageSize);

    /**
     * Fetches the next page using a previously returned cursor.
     */
    Page fetchNextPage(CollectionName collection, PageCursor cursor, int pageSize);

    // -------------------------------------------------------------------
    //  Maintenance
    // -------------------------------------------------------------------

    /**
     * Returns the approximate document count for the given collection.
     */
    long estimatedCount(CollectionName collection);

    /**
     * Deletes all documents older than the given cutoff. Used by
     * {@link AuditRetentionPolicy} for lifecycle management.
     *
     * @return the number of documents deleted
     */
    long deleteOlderThan(CollectionName collection, Instant cutoff);

    /**
     * Archives documents in the date range to cold storage (S3, GCS, etc.)
     * and then deletes them from the primary store.
     *
     * @return the number of documents archived
     */
    long archiveRange(CollectionName collection,
                      Instant from,
                      Instant to,
                      String archiveDestination);
}
