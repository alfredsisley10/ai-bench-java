package com.omnibank.channel.studentchecking.kiosk;

import java.util.Set;

/**
 * Feature gating for StudentChecking on In-Branch Kiosk. Not every capability is
 * available on every channel — ATMs cannot open accounts, IVR
 * cannot render PDFs, API cannot present a PIN pad, etc.
 */
public final class StudentCheckingKioskFeatureGating {

    private final boolean canOpenAccount = true;
    private final boolean canCloseAccount = false;
    private final boolean supportsOffline = false;

    public boolean canOpenAccount() { return canOpenAccount; }
    public boolean canCloseAccount() { return canCloseAccount; }
    public boolean supportsOffline() { return supportsOffline; }

    public Set<String> availableFeatures() {
        return switch ("KIOSK") {
            case "WEB" -> Set.of(
                    "VIEW_BALANCE", "VIEW_HISTORY", "TRANSFER_INTERNAL",
                    "TRANSFER_EXTERNAL", "BILLPAY", "MOBILE_DEPOSIT",
                    "STATEMENTS", "DISPUTES", "CARD_CONTROLS", "OPEN_ACCOUNT",
                    "CLOSE_ACCOUNT", "UPDATE_PROFILE");
            case "MOBILE" -> Set.of(
                    "VIEW_BALANCE", "VIEW_HISTORY", "TRANSFER_INTERNAL",
                    "TRANSFER_EXTERNAL", "BILLPAY", "MOBILE_DEPOSIT",
                    "STATEMENTS", "DISPUTES", "CARD_CONTROLS", "ZELLE",
                    "OPEN_ACCOUNT", "UPDATE_PROFILE");
            case "BRANCH" -> Set.of(
                    "VIEW_BALANCE", "VIEW_HISTORY",
                    "CASH_DEPOSIT", "CASH_WITHDRAWAL", "WIRE_INITIATION",
                    "OPEN_ACCOUNT", "CLOSE_ACCOUNT", "NOTARIZATION",
                    "COIN_COUNTING", "SAFE_DEPOSIT_BOX");
            case "ATM" -> Set.of(
                    "CASH_WITHDRAWAL", "CASH_DEPOSIT", "VIEW_BALANCE",
                    "TRANSFER_INTERNAL");
            case "CALL_CENTER" -> Set.of(
                    "VIEW_BALANCE", "VIEW_HISTORY", "TRANSFER_INTERNAL",
                    "OPEN_ACCOUNT", "CLOSE_ACCOUNT", "DISPUTES",
                    "STOP_PAYMENT", "WIRE_INITIATION");
            case "IVR" -> Set.of(
                    "VIEW_BALANCE", "LAST_FIVE_TRANSACTIONS",
                    "PAY_BILL_FROM_SAVED_PAYEE");
            case "API" -> Set.of(
                    "VIEW_BALANCE", "VIEW_HISTORY", "TRANSFER_INTERNAL",
                    "TRANSFER_EXTERNAL", "STATEMENTS");
            case "KIOSK" -> Set.of(
                    "VIEW_BALANCE", "OPEN_ACCOUNT", "CARD_REPLACEMENT",
                    "UPDATE_ADDRESS");
            default -> Set.of("VIEW_BALANCE");
        };
    }

    public boolean isFeatureAvailable(String feature) {
        return availableFeatures().contains(feature);
    }
}
