package com.omnibank.channel.rewardschecking.branch;

import java.util.Map;

/**
 * Translates internal error codes into channel-appropriate user
 * messages for RewardsChecking on Branch Teller. Web can show technical
 * detail; IVR must be fully spoken; ATM has a single short line.
 */
public final class RewardsCheckingBranchErrorTranslator {

    private static final Map<String, String> INTERNAL_TO_CHANNEL = Map.ofEntries(
            Map.entry("ACCT-100", "Account not found"),
            Map.entry("ACCT-200", "Account is closed"),
            Map.entry("ACCT-300", "Account is frozen"),
            Map.entry("AUTH-100", "Authentication failed"),
            Map.entry("AUTH-200", "Session expired"),
            Map.entry("AUTH-300", "Too many failed attempts"),
            Map.entry("FUND-100", "Insufficient funds"),
            Map.entry("FUND-200", "Pending holds reduce available balance"),
            Map.entry("LIM-100", "Daily limit exceeded"),
            Map.entry("LIM-200", "Monthly limit exceeded"),
            Map.entry("NET-100", "Network timeout — please retry"),
            Map.entry("NET-200", "Downstream service unavailable"),
            Map.entry("COMP-100", "Compliance review required"),
            Map.entry("COMP-200", "Transaction blocked by fraud review")
    );

    public String translate(String internalCode) {
        String base = INTERNAL_TO_CHANNEL.getOrDefault(internalCode, "Unable to complete request");
        return shapeForChannel(base);
    }

    public String translateWithFallback(String internalCode, String fallback) {
        String base = INTERNAL_TO_CHANNEL.get(internalCode);
        return shapeForChannel(base != null ? base : fallback);
    }

    private String shapeForChannel(String message) {
        return switch ("BRANCH") {
            case "ATM", "KIOSK" -> truncate(message, 40);
            case "IVR" -> spellOut(message);
            case "API" -> message; // raw passthrough for machine clients
            default -> message;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private String spellOut(String s) {
        if (s == null) return "";
        // Expand common abbreviations and symbols for voice.
        return s.replace("$", "dollars ")
                .replace("%", "percent ")
                .replace("&", "and ")
                .replace("/", " or ");
    }
}
