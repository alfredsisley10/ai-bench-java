package com.omnibank.fraud.internal;

import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Device fingerprint management and matching engine. Compares device
 * characteristics to known profiles, maintains per-customer device trust
 * scores, detects new or suspicious devices, and manages the device
 * enrollment lifecycle.
 *
 * <p>Device fingerprint components:
 * <ul>
 *   <li><b>Browser/app:</b> User agent, browser version, installed plugins</li>
 *   <li><b>Hardware:</b> Screen resolution, color depth, timezone offset, cores</li>
 *   <li><b>Network:</b> IP geolocation, ASN, VPN/proxy detection</li>
 *   <li><b>Behavioral:</b> Typing cadence hash, mouse movement pattern</li>
 * </ul>
 *
 * <p>Trust scoring algorithm:
 * <ul>
 *   <li>New device starts at trust score 0</li>
 *   <li>Each successful transaction from the device increases trust (+5, capped at 100)</li>
 *   <li>Failed fraud checks decrease trust (-20)</li>
 *   <li>Long idle periods (90+ days) decay trust (-30)</li>
 *   <li>Device enrolled via secure channel (branch, 2FA) starts at 50</li>
 * </ul>
 */
public class DeviceFingerprintMatcher {

    private static final Logger log = LoggerFactory.getLogger(DeviceFingerprintMatcher.class);

    private static final int INITIAL_TRUST_SCORE = 0;
    private static final int ENROLLED_TRUST_SCORE = 50;
    private static final int MAX_TRUST_SCORE = 100;
    private static final int TRUST_INCREMENT_ON_SUCCESS = 5;
    private static final int TRUST_DECREMENT_ON_FRAUD = 20;
    private static final int TRUST_DECAY_ON_IDLE = 30;
    private static final Duration IDLE_THRESHOLD = Duration.ofDays(90);
    private static final int MAX_DEVICES_PER_CUSTOMER = 10;

    /** Minimum similarity score (0-100) to consider two fingerprints a match. */
    private static final int MATCH_THRESHOLD = 75;

    /** Similarity score below which the device is flagged as suspicious. */
    private static final int SUSPICIOUS_THRESHOLD = 40;

    /**
     * Device fingerprint data collected from the client.
     */
    record DeviceFingerprint(
            String fingerprintId,
            String userAgent,
            String screenResolution,
            int colorDepth,
            int timezoneOffset,
            int hardwareConcurrency,
            String language,
            String platform,
            boolean touchSupport,
            String ipAddress,
            String ipCountry,
            String ipAsn,
            boolean vpnDetected,
            boolean proxyDetected,
            String canvasHash,
            String webglHash,
            String audioHash
    ) {
        public DeviceFingerprint {
            Objects.requireNonNull(fingerprintId, "fingerprintId");
        }
    }

    /**
     * Stored device profile with trust tracking.
     */
    record DeviceProfile(
            UUID deviceId,
            CustomerId customer,
            DeviceFingerprint fingerprint,
            int trustScore,
            Instant enrolledAt,
            Instant lastSeenAt,
            int successfulTransactions,
            int failedFraudChecks,
            EnrollmentChannel enrollmentChannel,
            boolean active
    ) {}

    enum EnrollmentChannel {
        BRANCH_VERIFIED, TWO_FACTOR_AUTH, FIRST_USE, CUSTOMER_SELF_SERVICE
    }

    sealed interface MatchResult permits
            MatchResult.TrustedDevice,
            MatchResult.KnownDeviceLowTrust,
            MatchResult.NewDevice,
            MatchResult.SuspiciousDevice {

        record TrustedDevice(UUID deviceId, int trustScore, int similarityScore,
                              String matchedFingerprint) implements MatchResult {}

        record KnownDeviceLowTrust(UUID deviceId, int trustScore, int similarityScore,
                                    List<String> concerns) implements MatchResult {}

        record NewDevice(String fingerprintId, List<String> characteristics)
                implements MatchResult {}

        record SuspiciousDevice(String fingerprintId, List<String> suspicionReasons,
                                 int bestSimilarityScore) implements MatchResult {}
    }

    /** In-memory device store. Production would use a persistent store with TTL. */
    private final ConcurrentHashMap<String, List<DeviceProfile>> customerDevices
            = new ConcurrentHashMap<>();

    private final Clock clock;

    public DeviceFingerprintMatcher(Clock clock) {
        this.clock = clock;
    }

    /**
     * Match an incoming device fingerprint against the customer's known devices.
     * Returns the match result with trust assessment.
     */
    public MatchResult match(CustomerId customer, DeviceFingerprint fingerprint) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(fingerprint, "fingerprint");

        List<DeviceProfile> knownDevices = getCustomerDevices(customer);
        if (knownDevices.isEmpty()) {
            log.debug("No known devices for customer {}. First-time device: {}",
                    customer, fingerprint.fingerprintId());
            return new MatchResult.NewDevice(fingerprint.fingerprintId(),
                    describeDeviceCharacteristics(fingerprint));
        }

        DeviceProfile bestMatch = null;
        int bestSimilarity = 0;

        for (DeviceProfile profile : knownDevices) {
            if (!profile.active()) continue;
            int similarity = computeSimilarity(fingerprint, profile.fingerprint());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = profile;
            }
        }

        if (bestMatch == null || bestSimilarity < SUSPICIOUS_THRESHOLD) {
            List<String> reasons = analyzeSuspicion(fingerprint, knownDevices);
            log.warn("Suspicious device for customer {}: similarity={}, reasons={}",
                    customer, bestSimilarity, reasons);
            return new MatchResult.SuspiciousDevice(fingerprint.fingerprintId(),
                    reasons, bestSimilarity);
        }

        if (bestSimilarity < MATCH_THRESHOLD) {
            return new MatchResult.NewDevice(fingerprint.fingerprintId(),
                    describeDeviceCharacteristics(fingerprint));
        }

        applyIdleDecay(bestMatch);

        if (bestMatch.trustScore() >= 50) {
            return new MatchResult.TrustedDevice(bestMatch.deviceId(), bestMatch.trustScore(),
                    bestSimilarity, bestMatch.fingerprint().fingerprintId());
        }

        List<String> concerns = assessTrustConcerns(bestMatch);
        return new MatchResult.KnownDeviceLowTrust(bestMatch.deviceId(),
                bestMatch.trustScore(), bestSimilarity, concerns);
    }

    /**
     * Enroll a new device for a customer. Validates against the per-customer
     * device limit and sets the initial trust score based on enrollment channel.
     */
    public DeviceProfile enrollDevice(CustomerId customer, DeviceFingerprint fingerprint,
                                       EnrollmentChannel channel) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(channel, "channel");

        List<DeviceProfile> existing = getCustomerDevices(customer);
        if (existing.size() >= MAX_DEVICES_PER_CUSTOMER) {
            evictOldestDevice(customer, existing);
        }

        int initialTrust = switch (channel) {
            case BRANCH_VERIFIED -> ENROLLED_TRUST_SCORE + 20;
            case TWO_FACTOR_AUTH -> ENROLLED_TRUST_SCORE;
            case CUSTOMER_SELF_SERVICE -> ENROLLED_TRUST_SCORE - 10;
            case FIRST_USE -> INITIAL_TRUST_SCORE;
        };

        Instant now = Timestamp.now(clock);
        DeviceProfile profile = new DeviceProfile(
                UUID.randomUUID(), customer, fingerprint,
                Math.min(initialTrust, MAX_TRUST_SCORE),
                now, now, 0, 0, channel, true
        );

        customerDevices
                .computeIfAbsent(customer.toString(), k -> new ArrayList<>())
                .add(profile);

        log.info("Enrolled device {} for customer {} via {} (trust: {})",
                profile.deviceId(), customer, channel, profile.trustScore());
        return profile;
    }

    /**
     * Record a successful transaction from a device, incrementing its trust score.
     */
    public Optional<DeviceProfile> recordSuccessfulTransaction(CustomerId customer,
                                                                 UUID deviceId) {
        return findDevice(customer, deviceId).map(profile -> {
            int newTrust = Math.min(profile.trustScore() + TRUST_INCREMENT_ON_SUCCESS,
                    MAX_TRUST_SCORE);
            DeviceProfile updated = new DeviceProfile(
                    profile.deviceId(), profile.customer(), profile.fingerprint(),
                    newTrust, profile.enrolledAt(), Timestamp.now(clock),
                    profile.successfulTransactions() + 1, profile.failedFraudChecks(),
                    profile.enrollmentChannel(), profile.active()
            );
            replaceDevice(customer, deviceId, updated);
            return updated;
        });
    }

    /**
     * Record a failed fraud check against a device, decrementing trust.
     */
    public Optional<DeviceProfile> recordFraudFlag(CustomerId customer, UUID deviceId) {
        return findDevice(customer, deviceId).map(profile -> {
            int newTrust = Math.max(profile.trustScore() - TRUST_DECREMENT_ON_FRAUD, 0);
            DeviceProfile updated = new DeviceProfile(
                    profile.deviceId(), profile.customer(), profile.fingerprint(),
                    newTrust, profile.enrolledAt(), profile.lastSeenAt(),
                    profile.successfulTransactions(), profile.failedFraudChecks() + 1,
                    profile.enrollmentChannel(), newTrust > 0
            );
            replaceDevice(customer, deviceId, updated);

            if (!updated.active()) {
                log.warn("Device {} deactivated for customer {} due to fraud flags",
                        deviceId, customer);
            }
            return updated;
        });
    }

    /**
     * Deactivate a specific device (customer-initiated or fraud response).
     */
    public boolean deactivateDevice(CustomerId customer, UUID deviceId) {
        return findDevice(customer, deviceId).map(profile -> {
            DeviceProfile deactivated = new DeviceProfile(
                    profile.deviceId(), profile.customer(), profile.fingerprint(),
                    0, profile.enrolledAt(), profile.lastSeenAt(),
                    profile.successfulTransactions(), profile.failedFraudChecks(),
                    profile.enrollmentChannel(), false
            );
            replaceDevice(customer, deviceId, deactivated);
            log.info("Device {} deactivated for customer {}", deviceId, customer);
            return true;
        }).orElse(false);
    }

    /**
     * Compute similarity score (0-100) between two device fingerprints.
     * Each component contributes a weighted score.
     */
    int computeSimilarity(DeviceFingerprint incoming, DeviceFingerprint known) {
        int score = 0;
        int totalWeight = 0;

        // User agent (weight 15) — fuzzy match on major version
        totalWeight += 15;
        if (incoming.userAgent() != null && known.userAgent() != null) {
            score += similar(incoming.userAgent(), known.userAgent()) ? 15 : 0;
        }

        // Screen resolution (weight 10)
        totalWeight += 10;
        if (Objects.equals(incoming.screenResolution(), known.screenResolution())) {
            score += 10;
        }

        // Timezone (weight 10)
        totalWeight += 10;
        if (incoming.timezoneOffset() == known.timezoneOffset()) {
            score += 10;
        }

        // Platform (weight 10)
        totalWeight += 10;
        if (Objects.equals(incoming.platform(), known.platform())) {
            score += 10;
        }

        // Language (weight 5)
        totalWeight += 5;
        if (Objects.equals(incoming.language(), known.language())) {
            score += 5;
        }

        // Hardware concurrency (weight 5)
        totalWeight += 5;
        if (incoming.hardwareConcurrency() == known.hardwareConcurrency()) {
            score += 5;
        }

        // Canvas hash (weight 15) — strong device identifier
        totalWeight += 15;
        if (incoming.canvasHash() != null && incoming.canvasHash().equals(known.canvasHash())) {
            score += 15;
        }

        // WebGL hash (weight 15) — strong device identifier
        totalWeight += 15;
        if (incoming.webglHash() != null && incoming.webglHash().equals(known.webglHash())) {
            score += 15;
        }

        // Audio hash (weight 10)
        totalWeight += 10;
        if (incoming.audioHash() != null && incoming.audioHash().equals(known.audioHash())) {
            score += 10;
        }

        // IP country match (weight 5) — same country, not exact IP
        totalWeight += 5;
        if (incoming.ipCountry() != null && incoming.ipCountry().equals(known.ipCountry())) {
            score += 5;
        }

        return totalWeight > 0 ? (score * 100) / totalWeight : 0;
    }

    private boolean similar(String a, String b) {
        if (a == null || b == null) return false;
        // Simplified: check first 30 chars (covers browser family + major version)
        String prefixA = a.length() > 30 ? a.substring(0, 30) : a;
        String prefixB = b.length() > 30 ? b.substring(0, 30) : b;
        return prefixA.equals(prefixB);
    }

    private List<String> analyzeSuspicion(DeviceFingerprint incoming,
                                           List<DeviceProfile> knownDevices) {
        List<String> reasons = new ArrayList<>();

        if (incoming.vpnDetected()) {
            reasons.add("VPN detected");
        }
        if (incoming.proxyDetected()) {
            reasons.add("Proxy detected");
        }

        boolean countryMismatch = knownDevices.stream()
                .filter(DeviceProfile::active)
                .noneMatch(d -> Objects.equals(d.fingerprint().ipCountry(), incoming.ipCountry()));
        if (countryMismatch && incoming.ipCountry() != null) {
            reasons.add("IP country mismatch: " + incoming.ipCountry());
        }

        boolean platformMismatch = knownDevices.stream()
                .filter(DeviceProfile::active)
                .noneMatch(d -> Objects.equals(d.fingerprint().platform(), incoming.platform()));
        if (platformMismatch && incoming.platform() != null) {
            reasons.add("Unknown platform: " + incoming.platform());
        }

        if (reasons.isEmpty()) {
            reasons.add("Low similarity to all known devices");
        }

        return reasons;
    }

    private List<String> describeDeviceCharacteristics(DeviceFingerprint fp) {
        List<String> chars = new ArrayList<>();
        if (fp.platform() != null) chars.add("Platform: " + fp.platform());
        if (fp.screenResolution() != null) chars.add("Screen: " + fp.screenResolution());
        if (fp.ipCountry() != null) chars.add("Country: " + fp.ipCountry());
        if (fp.vpnDetected()) chars.add("VPN: yes");
        return chars;
    }

    private List<String> assessTrustConcerns(DeviceProfile profile) {
        List<String> concerns = new ArrayList<>();
        if (profile.trustScore() < 20) concerns.add("Very low trust score");
        if (profile.failedFraudChecks() > 0) {
            concerns.add("Prior fraud flags: " + profile.failedFraudChecks());
        }
        if (profile.successfulTransactions() < 3) {
            concerns.add("Limited transaction history");
        }
        return concerns;
    }

    private void applyIdleDecay(DeviceProfile profile) {
        if (profile.lastSeenAt() == null) return;
        Duration idle = Duration.between(profile.lastSeenAt(), Timestamp.now(clock));
        if (idle.compareTo(IDLE_THRESHOLD) > 0) {
            int decayed = Math.max(profile.trustScore() - TRUST_DECAY_ON_IDLE, 0);
            if (decayed != profile.trustScore()) {
                DeviceProfile updated = new DeviceProfile(
                        profile.deviceId(), profile.customer(), profile.fingerprint(),
                        decayed, profile.enrolledAt(), profile.lastSeenAt(),
                        profile.successfulTransactions(), profile.failedFraudChecks(),
                        profile.enrollmentChannel(), profile.active()
                );
                replaceDevice(profile.customer(), profile.deviceId(), updated);
            }
        }
    }

    private void evictOldestDevice(CustomerId customer, List<DeviceProfile> devices) {
        devices.stream()
                .filter(DeviceProfile::active)
                .min(java.util.Comparator.comparing(DeviceProfile::lastSeenAt))
                .ifPresent(oldest -> deactivateDevice(customer, oldest.deviceId()));
    }

    private Optional<DeviceProfile> findDevice(CustomerId customer, UUID deviceId) {
        return getCustomerDevices(customer).stream()
                .filter(d -> d.deviceId().equals(deviceId))
                .findFirst();
    }

    private void replaceDevice(CustomerId customer, UUID deviceId, DeviceProfile replacement) {
        List<DeviceProfile> devices = customerDevices.get(customer.toString());
        if (devices != null) {
            devices.replaceAll(d -> d.deviceId().equals(deviceId) ? replacement : d);
        }
    }

    private List<DeviceProfile> getCustomerDevices(CustomerId customer) {
        List<DeviceProfile> devices = customerDevices.get(customer.toString());
        return devices != null ? List.copyOf(devices) : List.of();
    }
}
