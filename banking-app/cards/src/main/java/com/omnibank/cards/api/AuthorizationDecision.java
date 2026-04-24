package com.omnibank.cards.api;

public record AuthorizationDecision(boolean approved, String code, String reason) {

    public static AuthorizationDecision approved(String code) {
        return new AuthorizationDecision(true, code, null);
    }

    public static AuthorizationDecision declined(String code, String reason) {
        return new AuthorizationDecision(false, code, reason);
    }
}
