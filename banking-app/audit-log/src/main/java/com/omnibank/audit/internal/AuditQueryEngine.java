package com.omnibank.audit.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Query engine for audit documents. Operates as a facade over
 * {@link AuditEventStore}, providing a composable filter DSL, aggregation
 * pipelines, and cursor-based pagination.
 *
 * <p>In production the heavy lifting is pushed down to the store
 * (MongoDB aggregation, Elasticsearch DSL). This engine provides a
 * store-agnostic query API that in-memory / test implementations can
 * evaluate locally and distributed implementations can translate to
 * native queries.
 */
public final class AuditQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryEngine.class);
    private static final DateTimeFormatter BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private final AuditEventStore store;

    public AuditQueryEngine(AuditEventStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    // -------------------------------------------------------------------
    //  Filter DSL
    // -------------------------------------------------------------------

    /**
     * Composable query filter. Sealed so pattern matching is exhaustive.
     */
    public sealed interface Filter permits
            Filter.TimeRange,
            Filter.ActorEquals,
            Filter.ActionEquals,
            Filter.ResourceTypeEquals,
            Filter.ResourceIdEquals,
            Filter.CategoryEquals,
            Filter.OutcomeEquals,
            Filter.FullText,
            Filter.And,
            Filter.Or {

        record TimeRange(Instant from, Instant to) implements Filter {
            public TimeRange {
                Objects.requireNonNull(from, "from");
                Objects.requireNonNull(to, "to");
                if (!from.isBefore(to)) {
                    throw new IllegalArgumentException("from must be before to");
                }
            }
        }

        record ActorEquals(String principalId) implements Filter {
            public ActorEquals { Objects.requireNonNull(principalId, "principalId"); }
        }

        record ActionEquals(String action) implements Filter {
            public ActionEquals { Objects.requireNonNull(action, "action"); }
        }

        record ResourceTypeEquals(String resourceType) implements Filter {
            public ResourceTypeEquals { Objects.requireNonNull(resourceType, "resourceType"); }
        }

        record ResourceIdEquals(String resourceId) implements Filter {
            public ResourceIdEquals { Objects.requireNonNull(resourceId, "resourceId"); }
        }

        record CategoryEquals(AuditDocument.Category category) implements Filter {
            public CategoryEquals { Objects.requireNonNull(category, "category"); }
        }

        record OutcomeEquals(AuditDocument.Outcome outcome) implements Filter {
            public OutcomeEquals { Objects.requireNonNull(outcome, "outcome"); }
        }

        record FullText(String queryText) implements Filter {
            public FullText { Objects.requireNonNull(queryText, "queryText"); }
        }

        record And(List<Filter> filters) implements Filter {
            public And { filters = List.copyOf(filters); }
        }

        record Or(List<Filter> filters) implements Filter {
            public Or { filters = List.copyOf(filters); }
        }
    }

    /**
     * Converts a {@link Filter} into a {@link Predicate} that can be applied
     * to in-memory document streams. Production implementations should
     * translate to native query language instead.
     */
    public Predicate<AuditDocument> toPredicate(Filter filter) {
        return switch (filter) {
            case Filter.TimeRange tr -> doc ->
                    !doc.timestamp().isBefore(tr.from()) && doc.timestamp().isBefore(tr.to());
            case Filter.ActorEquals ae -> doc ->
                    doc.actor() != null && ae.principalId().equals(doc.actor().principalId());
            case Filter.ActionEquals ae -> doc ->
                    ae.action().equals(doc.action());
            case Filter.ResourceTypeEquals rte -> doc ->
                    doc.resource() != null && rte.resourceType().equals(doc.resource().resourceType());
            case Filter.ResourceIdEquals rie -> doc ->
                    doc.resource() != null && rie.resourceId().equals(doc.resource().resourceId());
            case Filter.CategoryEquals ce -> doc ->
                    ce.category() == doc.category();
            case Filter.OutcomeEquals oe -> doc ->
                    oe.outcome() == doc.outcome();
            case Filter.FullText ft -> doc ->
                    doc.fullTextSearchable() != null
                            && doc.fullTextSearchable().toLowerCase().contains(ft.queryText().toLowerCase());
            case Filter.And and -> and.filters().stream()
                    .map(this::toPredicate)
                    .reduce(Predicate::and)
                    .orElse(doc -> true);
            case Filter.Or or -> or.filters().stream()
                    .map(this::toPredicate)
                    .reduce(Predicate::or)
                    .orElse(doc -> false);
        };
    }

    // -------------------------------------------------------------------
    //  Query execution
    // -------------------------------------------------------------------

    /**
     * Query parameters combining filter, pagination, and sort.
     *
     * @param collection target collection
     * @param filter     composed filter tree
     * @param pageSize   results per page (1..1000)
     * @param cursor     null for the first page
     * @param ascending  true = oldest first; false = newest first
     */
    public record Query(
            AuditEventStore.CollectionName collection,
            Filter filter,
            int pageSize,
            AuditEventStore.PageCursor cursor,
            boolean ascending
    ) {
        public Query {
            Objects.requireNonNull(collection, "collection");
            Objects.requireNonNull(filter, "filter");
            if (pageSize < 1 || pageSize > 1000) {
                throw new IllegalArgumentException("pageSize must be in [1, 1000]");
            }
        }

        public static Query of(AuditEventStore.CollectionName collection,
                                Filter filter,
                                int pageSize) {
            return new Query(collection, filter, pageSize, null, false);
        }
    }

    /**
     * Result of a query execution.
     *
     * @param documents matched documents for the current page
     * @param cursor    cursor for the next page (null if last page)
     * @param totalEstimate estimated total matching documents (-1 if unknown)
     * @param queryTimeMs   time in milliseconds to execute the query
     */
    public record QueryResult(
            List<AuditDocument> documents,
            AuditEventStore.PageCursor cursor,
            long totalEstimate,
            long queryTimeMs
    ) {}

    /**
     * Executes a query against the store.
     */
    public QueryResult execute(Query query) {
        long start = System.nanoTime();
        log.debug("Executing audit query on collection={} filter={}",
                query.collection(), query.filter());

        // Delegate to the store's paginated API for time-range filters
        if (query.filter() instanceof Filter.TimeRange tr && query.cursor() == null) {
            AuditEventStore.Page page = store.findByTimeRangePaginated(
                    query.collection(), tr.from(), tr.to(), query.pageSize());
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new QueryResult(page.documents(), page.cursor(),
                    page.cursor().totalCount(), elapsed);
        }

        // For cursor-based continuation
        if (query.cursor() != null) {
            AuditEventStore.Page page = store.fetchNextPage(
                    query.collection(), query.cursor(), query.pageSize());
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new QueryResult(page.documents(), page.cursor(),
                    page.cursor().totalCount(), elapsed);
        }

        // Fallback: full-scan with predicate (acceptable for small volumes or tests)
        Instant scanFrom = Instant.now().minus(365, ChronoUnit.DAYS);
        Instant scanTo = Instant.now().plus(1, ChronoUnit.DAYS);
        List<AuditDocument> all = store.findByTimeRange(
                query.collection(), scanFrom, scanTo, 50_000);

        Predicate<AuditDocument> predicate = toPredicate(query.filter());
        Comparator<AuditDocument> ordering = query.ascending()
                ? Comparator.comparing(AuditDocument::timestamp)
                : Comparator.comparing(AuditDocument::timestamp).reversed();

        List<AuditDocument> matched = all.stream()
                .filter(predicate)
                .sorted(ordering)
                .limit(query.pageSize())
                .toList();

        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        return new QueryResult(matched, null, matched.size(), elapsed);
    }

    // -------------------------------------------------------------------
    //  Aggregations
    // -------------------------------------------------------------------

    /**
     * Counts documents grouped by action type within a time range.
     */
    public Map<String, Long> countByAction(AuditEventStore.CollectionName collection,
                                           Instant from,
                                           Instant to) {
        return aggregate(collection, from, to, AuditDocument::action);
    }

    /**
     * Counts documents grouped by actor principal ID within a time range.
     */
    public Map<String, Long> countByActor(AuditEventStore.CollectionName collection,
                                          Instant from,
                                          Instant to) {
        return aggregate(collection, from, to,
                doc -> doc.actor() != null ? doc.actor().principalId() : "unknown");
    }

    /**
     * Counts documents grouped by outcome within a time range.
     */
    public Map<String, Long> countByOutcome(AuditEventStore.CollectionName collection,
                                            Instant from,
                                            Instant to) {
        return aggregate(collection, from, to,
                doc -> doc.outcome() != null ? doc.outcome().name() : "UNKNOWN");
    }

    /**
     * Time-series aggregation: counts documents in hourly buckets within a
     * time range. The map keys are ISO-formatted hour strings
     * (e.g. "2025-03-15T14").
     */
    public Map<String, Long> countByTimeBucket(AuditEventStore.CollectionName collection,
                                               Instant from,
                                               Instant to,
                                               ChronoUnit bucketUnit) {
        Objects.requireNonNull(bucketUnit, "bucketUnit");
        List<AuditDocument> docs = store.findByTimeRange(collection, from, to, 100_000);

        Map<String, Long> buckets = new LinkedHashMap<>();
        for (AuditDocument doc : docs) {
            Instant truncated = doc.timestamp().truncatedTo(bucketUnit);
            String bucketKey = BUCKET_FORMAT.format(truncated);
            buckets.merge(bucketKey, 1L, Long::sum);
        }
        return Collections.unmodifiableMap(buckets);
    }

    /**
     * Summary statistics for a time range.
     *
     * @param totalDocuments   total events in the range
     * @param uniqueActors     distinct actor count
     * @param uniqueActions    distinct action count
     * @param successCount     events with SUCCESS outcome
     * @param failureCount     events with FAILURE or DENIED outcome
     * @param avgDurationMs    average operation duration across all events
     */
    public record SummaryStats(
            long totalDocuments,
            long uniqueActors,
            long uniqueActions,
            long successCount,
            long failureCount,
            double avgDurationMs
    ) {}

    /**
     * Computes summary statistics for a time range.
     */
    public SummaryStats summarize(AuditEventStore.CollectionName collection,
                                  Instant from,
                                  Instant to) {
        List<AuditDocument> docs = store.findByTimeRange(collection, from, to, 100_000);

        long successes = docs.stream()
                .filter(d -> d.outcome() == AuditDocument.Outcome.SUCCESS)
                .count();
        long failures = docs.stream()
                .filter(d -> d.outcome() == AuditDocument.Outcome.FAILURE
                        || d.outcome() == AuditDocument.Outcome.DENIED)
                .count();
        long uniqueActors = docs.stream()
                .map(d -> d.actor() != null ? d.actor().principalId() : "")
                .distinct().count();
        long uniqueActions = docs.stream()
                .map(AuditDocument::action)
                .distinct().count();
        double avgDuration = docs.stream()
                .mapToLong(AuditDocument::durationMs)
                .average().orElse(0.0);

        return new SummaryStats(
                docs.size(), uniqueActors, uniqueActions,
                successes, failures, avgDuration);
    }

    // -------------------------------------------------------------------
    //  Internals
    // -------------------------------------------------------------------

    private Map<String, Long> aggregate(AuditEventStore.CollectionName collection,
                                        Instant from,
                                        Instant to,
                                        java.util.function.Function<AuditDocument, String> keyExtractor) {
        List<AuditDocument> docs = store.findByTimeRange(collection, from, to, 100_000);
        return docs.stream()
                .collect(Collectors.groupingBy(keyExtractor, LinkedHashMap::new, Collectors.counting()));
    }
}
