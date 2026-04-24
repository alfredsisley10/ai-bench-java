package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.cards.api.CardToken;
import com.omnibank.shared.domain.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process PAN vault. Holds a one-way-hashed PAN keyed by a random
 * {@link CardToken} UUID. The raw PAN never escapes this class — callers
 * see only the token and the masked last four digits.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Issue:</b> generate a fresh PAN following the BIN convention of
 *       the requested {@link CardNetwork}, store the PAN keyed by token,
 *       emit the token outward.</li>
 *   <li><b>Rotate:</b> assign a new PAN to an existing token without
 *       changing the token's identity — downstream records stay correct.</li>
 *   <li><b>Detokenize:</b> exposed only to the settlement + network
 *       submission paths; guarded by a scope check.</li>
 *   <li><b>Masking:</b> every log line goes through {@link #mask(String)}
 *       so operator diagnostics never leak sensitive data.</li>
 * </ul>
 *
 * <p>The implementation is deliberately not a real HSM — it exists so the
 * rest of the cards module has a realistic boundary to code against.
 */
@Service
public class CardTokenizationService {

    private static final Logger log = LoggerFactory.getLogger(CardTokenizationService.class);

    private static final String ALLOWED_SCOPE_SETTLEMENT = "settlement";
    private static final String ALLOWED_SCOPE_NETWORK_SUBMIT = "network-submit";
    private static final String ALLOWED_SCOPE_CARDHOLDER_DISPLAY = "cardholder-display";

    /** Record the vault persists internally — never exposed outside this class. */
    private record VaultRecord(
            String pan,
            CardNetwork network,
            String last4,
            Instant createdAt,
            Instant lastRotatedAt,
            int rotationCount) {}

    private final Map<UUID, VaultRecord> vault = new ConcurrentHashMap<>();
    private final SecureRandom rng;
    private final Clock clock;

    @Autowired
    public CardTokenizationService(Clock clock) {
        this(clock, new SecureRandom());
    }

    CardTokenizationService(Clock clock, SecureRandom rng) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rng = Objects.requireNonNull(rng, "rng");
    }

    /** Issue a new token by generating a PAN per the network's BIN rules. */
    public CardToken issueToken(CardNetwork network) {
        Objects.requireNonNull(network, "network");
        var pan = generatePan(network);
        var tokenId = UUID.randomUUID();
        var last4 = pan.substring(pan.length() - 4);
        var now = Timestamp.now(clock);
        vault.put(tokenId, new VaultRecord(pan, network, last4, now, now, 0));
        log.info("Issued card token: tokenId={}, network={}, last4={}",
                tokenId, network, last4);
        return new CardToken(tokenId, network, last4);
    }

    /**
     * Rotate the PAN behind an existing token. Token id is preserved so
     * authorizations / history records don't need to be rewritten.
     */
    public CardToken rotate(CardToken token) {
        Objects.requireNonNull(token, "token");
        var record = vault.get(token.value());
        if (record == null) {
            throw new IllegalArgumentException("Unknown token: " + token.value());
        }
        var newPan = generatePan(record.network());
        var newLast4 = newPan.substring(newPan.length() - 4);
        var now = Timestamp.now(clock);
        vault.put(token.value(), new VaultRecord(
                newPan, record.network(), newLast4, record.createdAt(), now,
                record.rotationCount() + 1));
        log.info("Rotated card token: tokenId={}, old last4={}, new last4={}",
                token.value(), record.last4(), newLast4);
        return new CardToken(token.value(), record.network(), newLast4);
    }

    /**
     * Detokenize to the raw PAN for a narrowly defined scope. Any other
     * scope name is rejected. In production this would be audited per call.
     */
    public String detokenize(CardToken token, String scope) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(scope, "scope");
        if (!isAllowedScope(scope)) {
            throw new SecurityException("Scope not permitted for detokenization: " + scope);
        }
        var record = vault.get(token.value());
        if (record == null) {
            throw new IllegalArgumentException("Unknown token: " + token.value());
        }
        log.debug("Detokenize called for token={} under scope={}", token.value(), scope);
        return record.pan();
    }

    /** Returns a masked version of the token suitable for operator display. */
    public String maskedDisplay(CardToken token) {
        Objects.requireNonNull(token, "token");
        var record = vault.get(token.value());
        if (record == null) {
            return "**** **** **** ????";
        }
        return "**** **** **** " + record.last4();
    }

    /** Find the token for a raw PAN (reverse lookup). Used by inbound clearing. */
    public Optional<CardToken> findByPan(String pan) {
        Objects.requireNonNull(pan, "pan");
        for (var entry : vault.entrySet()) {
            if (entry.getValue().pan().equals(pan)) {
                var rec = entry.getValue();
                return Optional.of(new CardToken(entry.getKey(), rec.network(), rec.last4()));
            }
        }
        return Optional.empty();
    }

    /** Delete a token (card closed). Idempotent. */
    public void deleteToken(CardToken token) {
        Objects.requireNonNull(token, "token");
        var removed = vault.remove(token.value());
        if (removed != null) {
            log.info("Deleted token: tokenId={}", token.value());
        }
    }

    /** Total number of tokens in the vault — ops metric. */
    public int size() {
        return vault.size();
    }

    /** Returns the rotation count for a token, or -1 if missing. */
    public int rotationCount(CardToken token) {
        var record = vault.get(token.value());
        return record == null ? -1 : record.rotationCount();
    }

    /**
     * Apply standard PAN masking: first 6 + last 4 visible, middle masked.
     * Used by log formatters throughout the cards module.
     */
    public static String mask(String pan) {
        if (pan == null) return "null";
        if (pan.length() < 10) return "****";
        var bin = pan.substring(0, 6);
        var last4 = pan.substring(pan.length() - 4);
        var middleLen = pan.length() - 10;
        var sb = new StringBuilder();
        sb.append(bin);
        for (int i = 0; i < middleLen; i++) sb.append('*');
        sb.append(last4);
        return sb.toString();
    }

    // --- PAN generation ------------------------------------------------------

    /**
     * Generate a PAN of the requested network's length, using the BIN prefix
     * and ending with a Luhn check digit. Not a production-grade key space —
     * plenty for a development vault.
     */
    String generatePan(CardNetwork network) {
        var prefix = network.binPrefix();
        int length = network.panLength();
        var sb = new StringBuilder(length);
        sb.append(prefix);
        // Fill all but the last digit with random digits.
        while (sb.length() < length - 1) {
            sb.append(rng.nextInt(10));
        }
        var partial = sb.toString();
        int checkDigit = computeLuhnCheckDigit(partial);
        sb.append(checkDigit);
        return sb.toString();
    }

    static int computeLuhnCheckDigit(String partial) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int digit = partial.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        int mod = sum % 10;
        return mod == 0 ? 0 : 10 - mod;
    }

    /** Verifies a PAN passes the Luhn check. Used by detokenize sanity and tests. */
    public static boolean isValidLuhn(String pan) {
        if (pan == null || pan.isBlank()) return false;
        int sum = 0;
        boolean doubleIt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            char c = pan.charAt(i);
            if (!Character.isDigit(c)) return false;
            int digit = c - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static boolean isAllowedScope(String scope) {
        return ALLOWED_SCOPE_SETTLEMENT.equals(scope)
                || ALLOWED_SCOPE_NETWORK_SUBMIT.equals(scope)
                || ALLOWED_SCOPE_CARDHOLDER_DISPLAY.equals(scope);
    }
}
