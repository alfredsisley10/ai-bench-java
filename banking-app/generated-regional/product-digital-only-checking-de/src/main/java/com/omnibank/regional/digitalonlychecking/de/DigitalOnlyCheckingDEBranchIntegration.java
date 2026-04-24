package com.omnibank.regional.digitalonlychecking.de;

import java.util.List;
import java.util.Objects;

/**
 * Branch / channel integration metadata for DigitalOnlyChecking in Delaware.
 * Tells the customer-portal and in-branch teller system which
 * workflows to show and which regional call-center queue to use.
 */
public final class DigitalOnlyCheckingDEBranchIntegration {

    public record Channel(String code, String displayName, boolean active, String regionQueue) {
        public Channel {
            Objects.requireNonNull(code);
            Objects.requireNonNull(displayName);
        }
    }

    private final DigitalOnlyCheckingDEProfile profile;

    public DigitalOnlyCheckingDEBranchIntegration() {
        this.profile = new DigitalOnlyCheckingDEProfile();
    }

    public DigitalOnlyCheckingDEBranchIntegration(DigitalOnlyCheckingDEProfile profile) {
        this.profile = profile;
    }

    public List<Channel> availableChannels() {
        return List.of(
                new Channel("WEB", "Online Banking", true, queueFor("WEB")),
                new Channel("MOBILE", "Mobile App", true, queueFor("MOBILE")),
                new Channel("BRANCH", "Branch", hasBranches(), queueFor("BRANCH")),
                new Channel("ATM", "ATM Network", true, queueFor("ATM")),
                new Channel("CALL_CENTER", "Phone Banking", true,
                        profile.offersSpanishLanguageSupport()
                                ? queueFor("CALL_CENTER") + "_BILINGUAL"
                                : queueFor("CALL_CENTER"))
        );
    }

    private String queueFor(String channel) {
        return profile.region() + "_" + channel;
    }

    private boolean hasBranches() {
        return switch (profile.stateCode()) {
            // Digital-first states with limited physical footprint
            case "AK", "ND", "SD", "WY", "MT" -> false;
            default -> true;
        };
    }

    public String primaryCallCenterQueue() {
        return queueFor("CALL_CENTER");
    }

    public boolean channelActive(String channelCode) {
        return availableChannels().stream()
                .anyMatch(c -> c.code().equals(channelCode) && c.active());
    }
}
