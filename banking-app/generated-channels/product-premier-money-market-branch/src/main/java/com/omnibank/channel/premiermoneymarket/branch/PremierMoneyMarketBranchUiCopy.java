package com.omnibank.channel.premiermoneymarket.branch;

import java.util.Locale;
import java.util.Map;

/**
 * Channel-tailored UI / audio copy for the PremierMoneyMarket account flow over
 * Branch Teller. Copy length and vocabulary differ: web has room for
 * long-form copy, ATM screens must fit a narrow line, IVR text is
 * read aloud and must avoid visual-only cues.
 */
public final class PremierMoneyMarketBranchUiCopy {

    private final boolean supportsSpanish;

    public PremierMoneyMarketBranchUiCopy() {
        this.supportsSpanish = true;
    }

    public String welcomeHeadline(Locale locale) {
        if (isSpanish(locale)) {
            return "Bienvenido a su cuenta premiermoneymarket";
        }
        return "Welcome to your premiermoneymarket account";
    }

    public String confirmDepositPrompt(Locale locale) {
        return switch ("BRANCH") {
            case "ATM" -> isSpanish(locale)
                    ? "Confirme depósito"
                    : "Confirm deposit";
            case "IVR" -> isSpanish(locale)
                    ? "Por favor confirme el monto del depósito presionando uno"
                    : "Please confirm the deposit amount by pressing one";
            case "WEB", "MOBILE" -> isSpanish(locale)
                    ? "¿Está seguro de realizar este depósito?"
                    : "Are you sure you want to make this deposit?";
            default -> "Confirm";
        };
    }

    public Map<String, String> errorMessageMap(Locale locale) {
        boolean spanish = isSpanish(locale);
        return Map.of(
                "INSUFFICIENT_FUNDS", spanish
                        ? "Fondos insuficientes para esta transacción"
                        : "Not enough funds for this transaction",
                "SESSION_EXPIRED", spanish
                        ? "La sesión ha expirado. Inicie sesión de nuevo."
                        : "Your session has expired. Please sign in again.",
                "DAILY_LIMIT_EXCEEDED", spanish
                        ? "Ha superado el límite diario"
                        : "Daily transaction limit exceeded",
                "INVALID_AMOUNT", spanish
                        ? "Monto inválido"
                        : "Invalid amount",
                "CHANNEL_UNAVAILABLE", spanish
                        ? "Este servicio no está disponible en este canal"
                        : "This service is not available on this channel"
        );
    }

    public int recommendedCopyMaxChars() {
        return switch ("BRANCH") {
            case "ATM", "KIOSK" -> 40;
            case "IVR" -> 120;
            case "MOBILE" -> 80;
            case "WEB" -> 280;
            case "API" -> 140;
            default -> 140;
        };
    }

    private boolean isSpanish(Locale locale) {
        if (!supportsSpanish) return false;
        return locale != null && "es".equalsIgnoreCase(locale.getLanguage());
    }
}
