package com.omnibank.statements.internal;

import com.omnibank.statements.internal.StatementDeliveryService.Channel;
import com.omnibank.statements.internal.StatementPreferencesManager.Frequency;
import com.omnibank.statements.internal.StatementPreferencesManager.Language;
import com.omnibank.statements.internal.StatementPreferencesManager.Preferences;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementPreferencesManagerTest {

    private StatementPreferencesManager prefs;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
        prefs = new StatementPreferencesManager(clock);
    }

    @Test
    void enroll_default_creates_record_with_monthly_en_mail() {
        Preferences p = prefs.enrollDefault(StatementTestFixtures.ALICE);
        assertThat(p.paperless()).isFalse();
        assertThat(p.primaryChannel()).isEqualTo(Channel.USPS_PHYSICAL_MAIL);
        assertThat(p.language()).isEqualTo(Language.EN);
        assertThat(p.frequency()).isEqualTo(Frequency.MONTHLY);
    }

    @Test
    void enroll_default_is_idempotent() {
        Preferences a = prefs.enrollDefault(StatementTestFixtures.ALICE);
        Preferences b = prefs.enrollDefault(StatementTestFixtures.ALICE);
        assertThat(b).isEqualTo(a);
    }

    @Test
    void set_paperless_flips_primary_channel_to_portal() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        Preferences p = prefs.setPaperless(StatementTestFixtures.ALICE, true, "web-ui");
        assertThat(p.paperless()).isTrue();
        assertThat(p.primaryChannel()).isEqualTo(Channel.SECURE_MESSAGE_PORTAL);
    }

    @Test
    void set_paperless_back_to_false_restores_physical_mail() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        prefs.setPaperless(StatementTestFixtures.ALICE, true, "web-ui");
        Preferences p = prefs.setPaperless(StatementTestFixtures.ALICE, false, "web-ui");
        assertThat(p.primaryChannel()).isEqualTo(Channel.USPS_PHYSICAL_MAIL);
    }

    @Test
    void set_channels_rejects_same_primary_and_fallback() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        assertThatThrownBy(() -> prefs.setChannels(StatementTestFixtures.ALICE,
                Channel.E_STATEMENT_SFTP, Channel.E_STATEMENT_SFTP, "csr"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void set_address_override_stores_trimmed_value() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        Preferences p = prefs.setAddressOverride(StatementTestFixtures.ALICE,
                "  100 Beach Blvd, Miami FL  ", "csr");
        assertThat(p.address()).contains("100 Beach Blvd, Miami FL");
    }

    @Test
    void blank_address_override_is_cleared() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        prefs.setAddressOverride(StatementTestFixtures.ALICE, "temp", null);
        Preferences p = prefs.setAddressOverride(StatementTestFixtures.ALICE, "   ", null);
        assertThat(p.address()).isEmpty();
    }

    @Test
    void language_change_to_es() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        Preferences p = prefs.setLanguage(StatementTestFixtures.ALICE, Language.ES, "customer");
        assertThat(p.language()).isEqualTo(Language.ES);
    }

    @Test
    void frequency_change_to_quarterly() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        Preferences p = prefs.setFrequency(StatementTestFixtures.ALICE, Frequency.QUARTERLY, "customer");
        assertThat(p.frequency()).isEqualTo(Frequency.QUARTERLY);
    }

    @Test
    void combined_group_add_and_remove_round_trip() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        prefs.addToCombinedGroup(StatementTestFixtures.ALICE,
                StatementTestFixtures.SAVINGS, "customer");
        Preferences after = prefs.get(StatementTestFixtures.ALICE);
        assertThat(after.combinedGroup()).contains(StatementTestFixtures.SAVINGS);

        prefs.removeFromCombinedGroup(StatementTestFixtures.ALICE,
                StatementTestFixtures.SAVINGS, "customer");
        assertThat(prefs.get(StatementTestFixtures.ALICE).combinedGroup()).isEmpty();
    }

    @Test
    void get_on_unknown_customer_throws() {
        assertThatThrownBy(() -> prefs.get(StatementTestFixtures.BOB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void audit_log_captures_each_mutation() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        prefs.setPaperless(StatementTestFixtures.ALICE, true, "web-ui");
        prefs.setLanguage(StatementTestFixtures.ALICE, Language.ES, "csr");
        var log = prefs.auditLog(StatementTestFixtures.ALICE);
        // enrollment + paperless + language = at least 3 entries.
        assertThat(log).hasSizeGreaterThanOrEqualTo(3);
        assertThat(log).anyMatch(e -> e.field().equals("paperless"));
        assertThat(log).anyMatch(e -> e.field().equals("language"));
    }

    @Test
    void enrollment_count_reflects_number_of_customers() {
        prefs.enrollDefault(StatementTestFixtures.ALICE);
        prefs.enrollDefault(StatementTestFixtures.BOB);
        assertThat(prefs.enrollmentCount()).isEqualTo(2);
    }
}
