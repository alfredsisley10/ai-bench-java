package com.omnibank.shared.security;

/**
 * Thread-local principal accessor. Populated by the auth filter at the edge
 * and read anywhere the current actor's identity must be recorded (audit,
 * authorization, rate limits).
 *
 * <p>ScopedValue would be nicer here, but we stay on ThreadLocal for Spring
 * compatibility across async boundaries (e.g. @Async methods).
 */
public final class PrincipalContext {

    private static final ThreadLocal<BankPrincipal> CURRENT = new ThreadLocal<>();

    private PrincipalContext() {}

    public static void set(BankPrincipal p) {
        CURRENT.set(p);
    }

    public static BankPrincipal current() {
        BankPrincipal p = CURRENT.get();
        return p != null ? p : BankPrincipal.system();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
