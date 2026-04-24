package com.omnibank.channel.highyieldsavings.mobile;

import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HighYieldSavingsMobileSessionConfigTest {

    @Test
    void session_config_has_positive_idle_timeout() {
        var cfg = new HighYieldSavingsMobileSessionConfig();
        assertThat(cfg.idleTimeout()).isGreaterThan(Duration.ZERO);
        assertThat(cfg.absoluteTimeout()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void latency_above_target_is_flagged() {
        var cfg = new HighYieldSavingsMobileSessionConfig();
        assertThat(cfg.exceedsLatencyTarget(cfg.targetLatencyMs() + 100)).isTrue();
        assertThat(cfg.exceedsLatencyTarget(0)).isFalse();
    }

    @Test
    void auth_policy_exposes_base_factors() {
        var auth = new HighYieldSavingsMobileAuthenticationPolicy();
        assertThat(auth.baseFactors()).isNotEmpty();
        assertThat(auth.maxFailedAttempts()).isGreaterThan(0);
    }

    @Test
    void step_up_triggers_are_defined() {
        var auth = new HighYieldSavingsMobileAuthenticationPolicy();
        assertThat(auth.stepUpTriggers()).isNotEmpty();
    }

    @Test
    void ui_copy_welcome_is_nonempty() {
        var ui = new HighYieldSavingsMobileUiCopy();
        assertThat(ui.welcomeHeadline(Locale.ENGLISH)).isNotBlank();
        assertThat(ui.recommendedCopyMaxChars()).isGreaterThan(0);
    }

    @Test
    void error_translator_handles_unknown_code() {
        var et = new HighYieldSavingsMobileErrorTranslator();
        assertThat(et.translate("DOES-NOT-EXIST")).isNotBlank();
    }

    @Test
    void feature_gating_exposes_at_least_view_balance() {
        var fg = new HighYieldSavingsMobileFeatureGating();
        assertThat(fg.isFeatureAvailable("VIEW_BALANCE")).isTrue();
    }

    @Test
    void event_tracker_counts_events() {
        var t = new HighYieldSavingsMobileEventTracker();
        t.track("welcome");
        t.track("welcome");
        assertThat(t.count("welcome")).isEqualTo(2);
        assertThat(t.totalEvents()).isEqualTo(2);
    }
}
