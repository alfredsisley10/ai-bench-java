package com.omnibank.channel.rewardschecking.mobile;

import java.time.Duration;
import java.util.Objects;

/**
 * Session configuration for RewardsChecking accessed through Mobile App.
 * Idle timeouts, session refresh intervals, and reauthentication
 * policies vary per channel: a branch teller session lives hours
 * while a web session expires in minutes.
 */
public final class RewardsCheckingMobileSessionConfig {

    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final Duration refreshInterval;
    private final int maxConcurrentSessions;
    private final boolean rememberDevice;
    private final int targetLatencyMs;
    private final String deviceClass;

    public RewardsCheckingMobileSessionConfig() {
        this.idleTimeout = defaultIdleTimeout();
        this.absoluteTimeout = defaultAbsoluteTimeout();
        this.refreshInterval = defaultRefreshInterval();
        this.maxConcurrentSessions = defaultMaxSessions();
        this.rememberDevice = defaultRememberDevice();
        this.targetLatencyMs = 300;
        this.deviceClass = "SMARTPHONE";
    }

    public Duration idleTimeout() { return idleTimeout; }
    public Duration absoluteTimeout() { return absoluteTimeout; }
    public Duration refreshInterval() { return refreshInterval; }
    public int maxConcurrentSessions() { return maxConcurrentSessions; }
    public boolean rememberDevice() { return rememberDevice; }
    public int targetLatencyMs() { return targetLatencyMs; }
    public String deviceClass() { return deviceClass; }

    public boolean exceedsLatencyTarget(long observedMs) {
        return observedMs > targetLatencyMs;
    }

    public Duration gracePeriodBeforeLogout() {
        return idleTimeout.multipliedBy(1).dividedBy(4);
    }

    public boolean shouldEnforceAbsoluteTimeout(Duration sessionAge) {
        Objects.requireNonNull(sessionAge, "sessionAge");
        return sessionAge.compareTo(absoluteTimeout) >= 0;
    }

    private Duration defaultIdleTimeout() {
        return switch ("MOBILE") {
            case "WEB" -> Duration.ofMinutes(10);
            case "MOBILE" -> Duration.ofMinutes(15);
            case "BRANCH" -> Duration.ofHours(4);
            case "ATM" -> Duration.ofSeconds(45);
            case "CALL_CENTER" -> Duration.ofMinutes(30);
            case "IVR" -> Duration.ofMinutes(5);
            case "API" -> Duration.ofMinutes(60);
            case "KIOSK" -> Duration.ofSeconds(90);
            default -> Duration.ofMinutes(10);
        };
    }

    private Duration defaultAbsoluteTimeout() {
        return switch ("MOBILE") {
            case "WEB", "MOBILE" -> Duration.ofHours(12);
            case "BRANCH" -> Duration.ofHours(12);
            case "ATM", "KIOSK" -> Duration.ofMinutes(3);
            case "CALL_CENTER" -> Duration.ofHours(2);
            case "IVR" -> Duration.ofMinutes(20);
            case "API" -> Duration.ofHours(24);
            default -> Duration.ofHours(8);
        };
    }

    private Duration defaultRefreshInterval() {
        return switch ("MOBILE") {
            case "WEB", "MOBILE", "BRANCH" -> Duration.ofMinutes(5);
            case "ATM", "KIOSK", "IVR" -> Duration.ofSeconds(30);
            case "CALL_CENTER" -> Duration.ofMinutes(10);
            case "API" -> Duration.ofMinutes(15);
            default -> Duration.ofMinutes(5);
        };
    }

    private int defaultMaxSessions() {
        return switch ("MOBILE") {
            case "WEB" -> 3;
            case "MOBILE" -> 2;
            case "API" -> 20;
            case "BRANCH", "CALL_CENTER" -> 1;
            default -> 1;
        };
    }

    private boolean defaultRememberDevice() {
        return switch ("MOBILE") {
            case "WEB", "MOBILE" -> true;
            default -> false;
        };
    }
}
