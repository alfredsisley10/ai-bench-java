package com.omnibank.audit.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rich audit document that captures the full context of a security-relevant
 * or compliance-relevant event in the OmniBank platform.
 *
 * <p>Designed for storage in a document-oriented database (MongoDB, Elasticsearch,
 * or equivalent). Uses Jackson annotations for deterministic serialisation.
 *
 * <p>Immutable once built. Use {@link Builder} for construction.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AuditDocument {

    // -------------------------------------------------------------------
    //  Nested value types
    // -------------------------------------------------------------------

    /**
     * The human or system principal that performed the action.
     *
     * @param principalId   unique identifier (user-id, service-account name)
     * @param principalType USER, SERVICE_ACCOUNT, SYSTEM, API_KEY
     * @param displayName   human-readable label
     * @param roles         comma-separated roles at the time of action
     */
    public record Actor(
            String principalId,
            String principalType,
            String displayName,
            String roles
    ) {
        public Actor {
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(principalType, "principalType");
        }
    }

    /**
     * The resource that was acted upon.
     *
     * @param resourceType e.g. "Account", "Payment", "ComplianceCase"
     * @param resourceId   natural key of the resource
     * @param ownerPartyId the party that owns the resource (customer-id, etc.)
     */
    public record Resource(
            String resourceType,
            String resourceId,
            String ownerPartyId
    ) {
        public Resource {
            Objects.requireNonNull(resourceType, "resourceType");
            Objects.requireNonNull(resourceId, "resourceId");
        }
    }

    /**
     * Before/after state snapshots for mutation events.
     *
     * @param before JSON representation of the resource before the mutation
     * @param after  JSON representation of the resource after the mutation
     */
    public record StateSnapshot(
            JsonNode before,
            JsonNode after
    ) {}

    /**
     * Client device and network context.
     *
     * @param ipAddress     originating IP (may be the load-balancer VIP for
     *                      internal service calls)
     * @param userAgent     HTTP User-Agent header
     * @param deviceId      device fingerprint or mobile device identifier
     * @param geoCountry    ISO 3166-1 alpha-2 country code
     * @param geoCity       approximate city name
     * @param geoLatitude   latitude (nullable)
     * @param geoLongitude  longitude (nullable)
     */
    public record DeviceInfo(
            String ipAddress,
            String userAgent,
            String deviceId,
            String geoCountry,
            String geoCity,
            Double geoLatitude,
            Double geoLongitude
    ) {}

    /**
     * Session context at the time of the event.
     *
     * @param sessionId       server-side session identifier
     * @param correlationId   distributed tracing correlation ID
     * @param authMethod      how the actor authenticated (MFA, SSO, API_KEY, etc.)
     * @param channelType     WEB, MOBILE, API, BATCH, INTERNAL
     */
    public record SessionInfo(
            String sessionId,
            String correlationId,
            String authMethod,
            String channelType
    ) {}

    /**
     * Audit event categories — used for retention policy mapping.
     */
    public enum Category {
        REGULATORY,
        SECURITY,
        FINANCIAL,
        OPERATIONAL,
        CONFIGURATION
    }

    /**
     * Outcome of the audited operation.
     */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        DENIED,
        PARTIAL
    }

    // -------------------------------------------------------------------
    //  Document fields
    // -------------------------------------------------------------------

    private final UUID id;
    private final Instant timestamp;
    private final String action;
    private final Category category;
    private final Outcome outcome;
    private final Actor actor;
    private final Resource resource;
    private final StateSnapshot stateSnapshot;
    private final DeviceInfo deviceInfo;
    private final SessionInfo sessionInfo;
    private final long durationMs;
    private final String errorMessage;
    private final Map<String, Object> metadata;
    private final String fullTextSearchable;

    @JsonCreator
    private AuditDocument(
            @JsonProperty("id") UUID id,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("action") String action,
            @JsonProperty("category") Category category,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("actor") Actor actor,
            @JsonProperty("resource") Resource resource,
            @JsonProperty("stateSnapshot") StateSnapshot stateSnapshot,
            @JsonProperty("deviceInfo") DeviceInfo deviceInfo,
            @JsonProperty("sessionInfo") SessionInfo sessionInfo,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("fullTextSearchable") String fullTextSearchable) {
        this.id = id;
        this.timestamp = timestamp;
        this.action = action;
        this.category = category;
        this.outcome = outcome;
        this.actor = actor;
        this.resource = resource;
        this.stateSnapshot = stateSnapshot;
        this.deviceInfo = deviceInfo;
        this.sessionInfo = sessionInfo;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.metadata = metadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.fullTextSearchable = fullTextSearchable;
    }

    // -------------------------------------------------------------------
    //  Accessors
    // -------------------------------------------------------------------

    public UUID id() { return id; }
    public Instant timestamp() { return timestamp; }
    public String action() { return action; }
    public Category category() { return category; }
    public Outcome outcome() { return outcome; }
    public Actor actor() { return actor; }
    public Resource resource() { return resource; }
    public StateSnapshot stateSnapshot() { return stateSnapshot; }
    public DeviceInfo deviceInfo() { return deviceInfo; }
    public SessionInfo sessionInfo() { return sessionInfo; }
    public long durationMs() { return durationMs; }
    public String errorMessage() { return errorMessage; }
    public Map<String, Object> metadata() { return metadata; }
    public String fullTextSearchable() { return fullTextSearchable; }

    // -------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private Instant timestamp = Instant.now();
        private String action;
        private Category category = Category.OPERATIONAL;
        private Outcome outcome = Outcome.SUCCESS;
        private Actor actor;
        private Resource resource;
        private StateSnapshot stateSnapshot;
        private DeviceInfo deviceInfo;
        private SessionInfo sessionInfo;
        private long durationMs;
        private String errorMessage;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder() {}

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder timestamp(Instant ts) { this.timestamp = ts; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder category(Category c) { this.category = c; return this; }
        public Builder outcome(Outcome o) { this.outcome = o; return this; }
        public Builder actor(Actor a) { this.actor = a; return this; }
        public Builder resource(Resource r) { this.resource = r; return this; }
        public Builder stateSnapshot(StateSnapshot ss) { this.stateSnapshot = ss; return this; }
        public Builder deviceInfo(DeviceInfo di) { this.deviceInfo = di; return this; }
        public Builder sessionInfo(SessionInfo si) { this.sessionInfo = si; return this; }
        public Builder durationMs(long ms) { this.durationMs = ms; return this; }
        public Builder errorMessage(String msg) { this.errorMessage = msg; return this; }

        public Builder metadata(String key, Object value) {
            metadata.put(key, value);
            return this;
        }

        public AuditDocument build() {
            Objects.requireNonNull(action, "action is required");
            Objects.requireNonNull(actor, "actor is required");
            Objects.requireNonNull(resource, "resource is required");

            String searchText = buildSearchableText();

            return new AuditDocument(
                    id, timestamp, action, category, outcome,
                    actor, resource, stateSnapshot, deviceInfo, sessionInfo,
                    durationMs, errorMessage, metadata, searchText);
        }

        private String buildSearchableText() {
            var sb = new StringBuilder(256);
            sb.append(action).append(' ');
            sb.append(actor.principalId()).append(' ');
            if (actor.displayName() != null) sb.append(actor.displayName()).append(' ');
            sb.append(resource.resourceType()).append(' ');
            sb.append(resource.resourceId()).append(' ');
            if (resource.ownerPartyId() != null) sb.append(resource.ownerPartyId()).append(' ');
            if (errorMessage != null) sb.append(errorMessage);
            return sb.toString().trim();
        }
    }
}
