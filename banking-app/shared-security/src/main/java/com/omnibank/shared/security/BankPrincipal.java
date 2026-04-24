package com.omnibank.shared.security;

import com.omnibank.shared.domain.CustomerId;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated actor inside Omnibank. Covers three personas:
 *   - CUSTOMER: a bank customer (retail or corporate) acting on their own behalf
 *   - EMPLOYEE: a bank staff member (teller, banker, ops)
 *   - SYSTEM: a back-end process (batch job, integration)
 */
public final class BankPrincipal implements Principal {

    public enum Kind { CUSTOMER, EMPLOYEE, SYSTEM }

    private final String name;
    private final Kind kind;
    private final CustomerId customerId; // null for EMPLOYEE / SYSTEM
    private final Set<String> roles;

    public BankPrincipal(String name, Kind kind, CustomerId customerId, Set<String> roles) {
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.customerId = customerId;
        this.roles = Set.copyOf(roles);
    }

    public static BankPrincipal system() {
        return new BankPrincipal("system", Kind.SYSTEM, null, Set.of("ROLE_SYSTEM"));
    }

    @Override public String getName() { return name; }
    public Kind kind() { return kind; }
    public CustomerId customerId() { return customerId; }
    public Set<String> roles() { return roles; }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
