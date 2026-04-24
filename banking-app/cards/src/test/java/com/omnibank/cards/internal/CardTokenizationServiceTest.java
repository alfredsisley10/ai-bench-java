package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.cards.api.CardToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTokenizationServiceTest {

    private Clock clock;
    private CardTokenizationService vault;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        vault = new CardTokenizationService(clock, new SecureRandom(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void issue_creates_token_with_correct_network_and_last4() {
        var token = vault.issueToken(CardNetwork.VISA);
        assertThat(token.network()).isEqualTo(CardNetwork.VISA);
        assertThat(token.last4()).hasSize(4);
    }

    @Test
    void issued_pan_passes_luhn_check() {
        var token = vault.issueToken(CardNetwork.VISA);
        var pan = vault.detokenize(token, "network-submit");
        assertThat(CardTokenizationService.isValidLuhn(pan)).isTrue();
    }

    @Test
    void pan_starts_with_network_bin_prefix() {
        var visa = vault.issueToken(CardNetwork.VISA);
        var amex = vault.issueToken(CardNetwork.AMEX);
        assertThat(vault.detokenize(visa, "network-submit")).startsWith("4");
        assertThat(vault.detokenize(amex, "network-submit")).startsWith("3");
    }

    @Test
    void detokenize_rejects_unknown_scope() {
        var token = vault.issueToken(CardNetwork.VISA);
        assertThatThrownBy(() -> vault.detokenize(token, "random-scope"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void detokenize_unknown_token_throws() {
        var fake = new CardToken(java.util.UUID.randomUUID(), CardNetwork.VISA, "0000");
        assertThatThrownBy(() -> vault.detokenize(fake, "network-submit"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotate_keeps_token_id_but_changes_pan_and_last4() {
        var original = vault.issueToken(CardNetwork.VISA);
        var originalPan = vault.detokenize(original, "network-submit");

        var rotated = vault.rotate(original);

        assertThat(rotated.value()).isEqualTo(original.value());
        var rotatedPan = vault.detokenize(rotated, "network-submit");
        assertThat(rotatedPan).isNotEqualTo(originalPan);
        assertThat(vault.rotationCount(rotated)).isEqualTo(1);
    }

    @Test
    void rotate_unknown_token_throws() {
        var fake = new CardToken(java.util.UUID.randomUUID(), CardNetwork.VISA, "0000");
        assertThatThrownBy(() -> vault.rotate(fake))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mask_hides_middle_digits() {
        var pan = "4532015112830366";
        var masked = CardTokenizationService.mask(pan);
        assertThat(masked).startsWith("453201");
        assertThat(masked).endsWith("0366");
        assertThat(masked).contains("*");
    }

    @Test
    void mask_handles_null_and_short_input() {
        assertThat(CardTokenizationService.mask(null)).isEqualTo("null");
        assertThat(CardTokenizationService.mask("123")).isEqualTo("****");
    }

    @Test
    void findByPan_returns_matching_token_or_empty() {
        var token = vault.issueToken(CardNetwork.VISA);
        var pan = vault.detokenize(token, "network-submit");

        assertThat(vault.findByPan(pan)).isPresent();
        assertThat(vault.findByPan("0000000000000000")).isEmpty();
    }

    @Test
    void deleteToken_is_idempotent() {
        var token = vault.issueToken(CardNetwork.VISA);
        vault.deleteToken(token);
        vault.deleteToken(token); // second call is a no-op
        assertThat(vault.size()).isZero();
    }

    @Test
    void luhn_validator_rejects_invalid_numbers() {
        assertThat(CardTokenizationService.isValidLuhn("4532015112830366")).isTrue();
        assertThat(CardTokenizationService.isValidLuhn("4532015112830367")).isFalse();
        assertThat(CardTokenizationService.isValidLuhn("4532015abcd")).isFalse();
        assertThat(CardTokenizationService.isValidLuhn(null)).isFalse();
        assertThat(CardTokenizationService.isValidLuhn("")).isFalse();
    }
}
