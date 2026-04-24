package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.statements.internal.StatementGenerator.Format;
import com.omnibank.statements.internal.StatementGenerator.RenderedStatement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encrypted long-term archive for rendered statements.
 *
 * <p>Federal Reserve Regulation DD (12 CFR § 1030.9) requires retention of
 * consumer-account statements for seven years. This service enforces that
 * retention through three coordinated mechanisms:
 * <ol>
 *   <li>An immutable content hash that lets retrieval verify no one has
 *       tampered with the archived bytes.</li>
 *   <li>A symmetric-key XOR envelope so at-rest bytes are not plaintext —
 *       production swaps this for KMS-backed AES-GCM but keeps the same
 *       wrap/unwrap contract.</li>
 *   <li>Legal-hold flags that block {@link #delete} even when retention has
 *       expired, so subpoenaed documents survive the automated purge job.</li>
 * </ol>
 *
 * <p>The storage layer is abstracted behind a simple map so tests (and the
 * in-memory benchmark runner) can plug in without a filesystem or object
 * store.
 */
public class StatementArchiveService {

    private static final Logger log = LoggerFactory.getLogger(StatementArchiveService.class);

    /** Reg DD retention floor — seven years from statement generation. */
    public static final Period RETENTION_PERIOD = Period.ofYears(7);

    /** Minimum key length accepted for the archive envelope. */
    public static final int MIN_KEY_BYTES = 16;

    /** Mutable reason-store for legal holds — keyed by statementId. */
    public enum ArchiveStatus {
        ACTIVE,
        PURGED,
        ON_LEGAL_HOLD
    }

    /** Persistent archive envelope. Bytes are encrypted (stub), hash is plaintext. */
    public record ArchivedStatement(
            String statementId,
            AccountNumber account,
            LocalDate cycleEnd,
            Format format,
            byte[] encryptedPayload,
            String contentHash,
            String checksum,
            Instant archivedAt,
            LocalDate retainUntil,
            ArchiveStatus status,
            String legalHoldReason
    ) {
        public ArchivedStatement {
            Objects.requireNonNull(statementId, "statementId");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(cycleEnd, "cycleEnd");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(encryptedPayload, "encryptedPayload");
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(checksum, "checksum");
            Objects.requireNonNull(archivedAt, "archivedAt");
            Objects.requireNonNull(retainUntil, "retainUntil");
            Objects.requireNonNull(status, "status");
        }
    }

    /** Result of a retrieval — bytes decrypted + integrity result. */
    public record RetrievedStatement(ArchivedStatement envelope, byte[] decryptedPayload, boolean integrityOk) {
        public RetrievedStatement {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(decryptedPayload, "decryptedPayload");
        }
    }

    private final Clock clock;
    private final byte[] archiveKey;
    private final Map<String, ArchivedStatement> storage = new ConcurrentHashMap<>();

    public StatementArchiveService(Clock clock, byte[] archiveKey) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(archiveKey, "archiveKey");
        if (archiveKey.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "archiveKey too short: %d bytes, need at least %d"
                            .formatted(archiveKey.length, MIN_KEY_BYTES));
        }
        this.archiveKey = archiveKey.clone();
    }

    /**
     * Archive a rendered statement. Returns the envelope that was persisted so
     * callers can record the retention date and checksum.
     */
    public ArchivedStatement archive(RenderedStatement rendered) {
        Objects.requireNonNull(rendered, "rendered");
        String statementId = rendered.content().statementId();
        if (storage.containsKey(statementId)) {
            log.debug("Statement {} already archived — returning existing envelope", statementId);
            return storage.get(statementId);
        }

        byte[] encrypted = xorEncrypt(rendered.payload());
        String checksum = sha256(rendered.payload());
        LocalDate cycleEnd = rendered.content().header().cycleEnd();
        LocalDate retainUntil = cycleEnd.plus(RETENTION_PERIOD);

        ArchivedStatement envelope = new ArchivedStatement(
                statementId,
                rendered.content().header().account(),
                cycleEnd,
                rendered.format(),
                encrypted,
                rendered.content().contentHash(),
                checksum,
                clock.instant(),
                retainUntil,
                ArchiveStatus.ACTIVE,
                null);
        storage.put(statementId, envelope);
        log.info("Archived statement {} format={} retainUntil={}", statementId, rendered.format(), retainUntil);
        return envelope;
    }

    /** Retrieve a statement on demand. Throws when the id is unknown. */
    public RetrievedStatement retrieve(String statementId) {
        Objects.requireNonNull(statementId, "statementId");
        ArchivedStatement envelope = storage.get(statementId);
        if (envelope == null) {
            throw new IllegalArgumentException("Statement not in archive: " + statementId);
        }
        if (envelope.status() == ArchiveStatus.PURGED) {
            throw new IllegalStateException("Statement has been purged: " + statementId);
        }
        byte[] decrypted = xorEncrypt(envelope.encryptedPayload()); // XOR is self-inverse
        boolean integrityOk = sha256(decrypted).equals(envelope.checksum());
        if (!integrityOk) {
            log.warn("Integrity check FAILED for statement {} — archive may be corrupt", statementId);
        }
        return new RetrievedStatement(envelope, decrypted, integrityOk);
    }

    /** List all envelopes for an account, newest first. */
    public List<ArchivedStatement> listByAccount(AccountNumber account) {
        Objects.requireNonNull(account, "account");
        List<ArchivedStatement> out = new ArrayList<>();
        for (ArchivedStatement a : storage.values()) {
            if (a.account().equals(account)) {
                out.add(a);
            }
        }
        Collections.sort(out, (a, b) -> b.cycleEnd().compareTo(a.cycleEnd()));
        return out;
    }

    /**
     * Apply a legal hold to a statement, making it indelible. Idempotent — a
     * statement already on hold is reset with the supplied reason (so holds
     * can be refreshed without a release-then-reapply dance).
     */
    public ArchivedStatement applyLegalHold(String statementId, String reason) {
        Objects.requireNonNull(statementId, "statementId");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Legal hold reason must be non-blank");
        }
        ArchivedStatement envelope = storage.get(statementId);
        if (envelope == null) {
            throw new IllegalArgumentException("Statement not in archive: " + statementId);
        }
        ArchivedStatement updated = withStatus(envelope, ArchiveStatus.ON_LEGAL_HOLD, reason);
        storage.put(statementId, updated);
        log.info("Legal hold placed on statement {} reason='{}'", statementId, reason);
        return updated;
    }

    /** Lift an existing legal hold, making the statement deletable again. */
    public ArchivedStatement releaseLegalHold(String statementId) {
        Objects.requireNonNull(statementId, "statementId");
        ArchivedStatement envelope = storage.get(statementId);
        if (envelope == null) {
            throw new IllegalArgumentException("Statement not in archive: " + statementId);
        }
        if (envelope.status() != ArchiveStatus.ON_LEGAL_HOLD) {
            log.debug("Statement {} not on hold — release is a no-op", statementId);
            return envelope;
        }
        ArchivedStatement updated = withStatus(envelope, ArchiveStatus.ACTIVE, null);
        storage.put(statementId, updated);
        log.info("Legal hold released on statement {}", statementId);
        return updated;
    }

    /**
     * Delete a single statement. Will refuse if the statement is on legal hold
     * or if the retention period has not yet passed — both conditions surface
     * as {@link IllegalStateException}.
     */
    public void delete(String statementId) {
        Objects.requireNonNull(statementId, "statementId");
        ArchivedStatement envelope = storage.get(statementId);
        if (envelope == null) {
            return;
        }
        if (envelope.status() == ArchiveStatus.ON_LEGAL_HOLD) {
            throw new IllegalStateException(
                    "Cannot delete — statement %s is under legal hold: %s"
                            .formatted(statementId, envelope.legalHoldReason()));
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("UTC"));
        if (today.isBefore(envelope.retainUntil())) {
            throw new IllegalStateException(
                    "Cannot delete — statement %s retained until %s (today=%s)"
                            .formatted(statementId, envelope.retainUntil(), today));
        }
        ArchivedStatement purged = withStatus(envelope, ArchiveStatus.PURGED, envelope.legalHoldReason());
        storage.put(statementId, purged);
        log.info("Statement {} marked purged (retention expired)", statementId);
    }

    /**
     * Purge all statements whose retention has expired and that are not on
     * hold. Returns the number of statements purged.
     */
    public int purgeExpired() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("UTC"));
        int purged = 0;
        for (ArchivedStatement env : storage.values()) {
            if (env.status() == ArchiveStatus.ACTIVE
                    && !today.isBefore(env.retainUntil())) {
                storage.put(env.statementId(),
                        withStatus(env, ArchiveStatus.PURGED, env.legalHoldReason()));
                purged++;
            }
        }
        log.info("Purge run complete, purged={} remaining={}", purged, storage.size());
        return purged;
    }

    /** Total number of envelopes currently tracked regardless of status. */
    public int totalCount() {
        return storage.size();
    }

    /** Look up an envelope without decrypting — useful for index views. */
    public Optional<ArchivedStatement> peek(String statementId) {
        return Optional.ofNullable(storage.get(statementId));
    }

    // ── internals ────────────────────────────────────────────────────────

    private ArchivedStatement withStatus(ArchivedStatement env, ArchiveStatus status, String reason) {
        return new ArchivedStatement(
                env.statementId(),
                env.account(),
                env.cycleEnd(),
                env.format(),
                env.encryptedPayload(),
                env.contentHash(),
                env.checksum(),
                env.archivedAt(),
                env.retainUntil(),
                status,
                reason);
    }

    private byte[] xorEncrypt(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = (byte) (input[i] ^ archiveKey[i % archiveKey.length]);
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(bytes);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Internal helper — exposed for tests that want to confirm bytes are not stored plaintext. */
    static boolean looksLikePlaintext(byte[] bytes, byte[] plaintext) {
        if (bytes.length != plaintext.length) return false;
        for (int i = 0; i < Math.min(bytes.length, 32); i++) {
            if (bytes[i] != plaintext[i]) return false;
        }
        return true;
    }

    // Byte-level helper used only by tests that want to corrupt the archive.
    void corruptForTest(String statementId) {
        ArchivedStatement env = storage.get(statementId);
        if (env == null) return;
        byte[] copy = env.encryptedPayload().clone();
        if (copy.length > 0) {
            copy[0] = (byte) (~copy[0]);
        }
        storage.put(statementId, new ArchivedStatement(
                env.statementId(), env.account(), env.cycleEnd(), env.format(),
                copy, env.contentHash(), env.checksum(), env.archivedAt(),
                env.retainUntil(), env.status(), env.legalHoldReason()));
    }

    /**
     * Helpers for debugging / benchmark reporting.
     */
    public String keyFingerprint() {
        byte[] key = archiveKey.clone();
        // Fold to a short hex fingerprint — never expose the full key.
        int fold = 0;
        for (byte b : key) {
            fold = (fold * 31) ^ (b & 0xFF);
        }
        return "KEY-%08X".formatted(fold);
    }

    /** Expose retention date calculation for external audit trail display. */
    public static LocalDate computeRetainUntil(LocalDate cycleEnd) {
        return cycleEnd.plus(RETENTION_PERIOD);
    }

    /** Convenience: raw byte-array handoff, pairs with {@link StatementGenerator} stubs. */
    public static byte[] safeCopy(byte[] input) {
        return input == null ? new byte[0] : input.clone();
    }

}
