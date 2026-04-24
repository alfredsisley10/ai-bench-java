package com.omnibank.channel.basicchecking.call_center;

import java.util.List;
import java.util.Objects;

/**
 * Authentication policy for BasicChecking on Phone Banking. Specifies the
 * required factors, step-up conditions, and device attestation
 * requirements for this channel.
 */
public final class BasicCheckingCallcenterAuthenticationPolicy {

    public enum Factor { PASSWORD, OTP_SMS, OTP_APP, BIOMETRIC, HARDWARE_TOKEN, VOICE, ID_DOC }

    public record StepUpTrigger(String reason, List<Factor> requiredFactors) {}

    private final String authStrength;
    private final List<Factor> baseFactors;
    private final boolean requiresDeviceAttestation;
    private final boolean allowsRememberMe;
    private final int maxFailedAttempts;

    public BasicCheckingCallcenterAuthenticationPolicy() {
        this.authStrength = "VOICE_VERIFICATION";
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
        return switch ("VOICE_VERIFICATION") {
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
        return switch ("CALL_CENTER") {
            case "MOBILE", "API" -> true;
            default -> false;
        };
    }

    private boolean rememberAllowed() {
        return switch ("CALL_CENTER") {
            case "WEB", "MOBILE" -> true;
            default -> false;
        };
    }

    private int defaultFailLimit() {
        return switch ("CALL_CENTER") {
            case "ATM", "KIOSK" -> 3;
            case "API" -> 10;
            default -> 5;
        };
    }
}
