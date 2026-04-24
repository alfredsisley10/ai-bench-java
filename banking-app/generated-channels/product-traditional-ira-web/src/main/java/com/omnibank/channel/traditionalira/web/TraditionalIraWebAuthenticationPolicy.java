package com.omnibank.channel.traditionalira.web;

import java.util.List;
import java.util.Objects;

/**
 * Authentication policy for TraditionalIra on Web Banking. Specifies the
 * required factors, step-up conditions, and device attestation
 * requirements for this channel.
 */
public final class TraditionalIraWebAuthenticationPolicy {

    public enum Factor { PASSWORD, OTP_SMS, OTP_APP, BIOMETRIC, HARDWARE_TOKEN, VOICE, ID_DOC }

    public record StepUpTrigger(String reason, List<Factor> requiredFactors) {}

    private final String authStrength;
    private final List<Factor> baseFactors;
    private final boolean requiresDeviceAttestation;
    private final boolean allowsRememberMe;
    private final int maxFailedAttempts;

    public TraditionalIraWebAuthenticationPolicy() {
        this.authStrength = "PASSWORD_2FA";
        this.baseFactors = defaultFactors();
        this.requiresDeviceAttestation = deviceAttestation();
        this.allowsRememberMe = rememberAllowed();
        this.maxFailedAttempts = defaultFailLimit();
    }

    public String authStrength() { return authStrength; }
    public List<Factor> baseFactors() { return baseFactors; }
    public boolean requiresDeviceAttestation() { return requiresDeviceAttestation; }
    public boolean allowsRememberMe() { return allowsRememberMe; }
    public int maxFailedAttempts() { return maxFailedAttempts; }

    public List<StepUpTrigger> stepUpTriggers() {
        return List.of(
                new StepUpTrigger("HIGH_VALUE_TRANSFER",
                        List.of(Factor.OTP_APP, Factor.BIOMETRIC)),
                new StepUpTrigger("NEW_BENEFICIARY",
                        List.of(Factor.OTP_SMS)),
                new StepUpTrigger("PROFILE_CHANGE",
                        List.of(Factor.OTP_APP)),
                new StepUpTrigger("DEVICE_CHANGE",
                        List.of(Factor.OTP_APP, Factor.BIOMETRIC))
        );
    }

    public boolean factorSatisfiesPolicy(Factor factor) {
        Objects.requireNonNull(factor, "factor");
        return baseFactors.contains(factor) || isStepUpFactor(factor);
    }

    public boolean isStepUpFactor(Factor factor) {
        return stepUpTriggers().stream()
                .flatMap(t -> t.requiredFactors().stream())
                .anyMatch(f -> f == factor);
    }

    private List<Factor> defaultFactors() {
        return switch ("PASSWORD_2FA") {
            case "PASSWORD_2FA" -> List.of(Factor.PASSWORD, Factor.OTP_APP);
            case "BIOMETRIC" -> List.of(Factor.BIOMETRIC, Factor.OTP_APP);
            case "ID_IN_PERSON" -> List.of(Factor.ID_DOC);
            case "CARD_PIN" -> List.of(Factor.HARDWARE_TOKEN);
            case "VOICE_VERIFICATION" -> List.of(Factor.VOICE, Factor.OTP_SMS);
            case "ACCOUNT_PIN" -> List.of(Factor.HARDWARE_TOKEN);
            case "OAUTH2" -> List.of(Factor.HARDWARE_TOKEN);
            default -> List.of(Factor.PASSWORD);
        };
    }

    private boolean deviceAttestation() {
        return switch ("WEB") {
            case "MOBILE", "API" -> true;
            default -> false;
        };
    }

    private boolean rememberAllowed() {
        return switch ("WEB") {
            case "WEB", "MOBILE" -> true;
            default -> false;
        };
    }

    private int defaultFailLimit() {
        return switch ("WEB") {
            case "ATM", "KIOSK" -> 3;
            case "API" -> 10;
            default -> 5;
        };
    }
}
