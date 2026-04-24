package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.statements.internal.StatementGenerator.GenerationRequest;
import com.omnibank.statements.internal.StatementGenerator.HoldSummary;
import com.omnibank.statements.internal.StatementGenerator.LineType;
import com.omnibank.statements.internal.StatementGenerator.StatementHeader;
import com.omnibank.statements.internal.StatementGenerator.StatementLineItem;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Test fixtures shared across the statements-internal test suite. Keep one
 * source of truth for account numbers, customer ids, and a simple line-item
 * bundle so every test stays legible.
 */
final class StatementTestFixtures {

    static final AccountNumber CHECKING = AccountNumber.of("OB-C-10000001");
    static final AccountNumber SAVINGS = AccountNumber.of("OB-R-20000002");
    static final CustomerId ALICE = CustomerId.of("11111111-1111-4111-8111-111111111111");
    static final CustomerId BOB = CustomerId.of("22222222-2222-4222-8222-222222222222");

    private StatementTestFixtures() {}

    static StatementHeader header(AccountNumber account, LocalDate start, LocalDate end) {
        return new StatementHeader(
                "Omnibank, N.A.",
                account,
                ALICE,
                "Alice Example",
                "123 Main St, Springfield IL",
                start, end,
                "Free Checking");
    }

    static List<StatementLineItem> sampleLineItems() {
        return List.of(
                new StatementLineItem(LocalDate.of(2026, 4, 2), "Direct deposit",
                        Money.of(2500, CurrencyCode.USD), LineType.DEPOSIT, "REF-DEP-1"),
                new StatementLineItem(LocalDate.of(2026, 4, 5), "Groceries",
                        Money.of(120, CurrencyCode.USD), LineType.WITHDRAWAL, "REF-WD-1"),
                new StatementLineItem(LocalDate.of(2026, 4, 10), "Monthly fee",
                        Money.of(15, CurrencyCode.USD), LineType.FEE, "REF-FEE-1"),
                new StatementLineItem(LocalDate.of(2026, 4, 15), "Interest earned",
                        Money.of("1.25", CurrencyCode.USD), LineType.INTEREST_EARNED, "REF-INT-1"));
    }

    static GenerationRequest simpleRequest() {
        return new GenerationRequest(
                header(CHECKING, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                Money.of(1000, CurrencyCode.USD),
                sampleLineItems(),
                List.of(new HoldSummary(
                        "Check hold #881",
                        Money.of(100, CurrencyCode.USD),
                        LocalDate.of(2026, 4, 8),
                        LocalDate.of(2026, 4, 12))));
    }

    static String newRandomCustomerIdStr() {
        return UUID.randomUUID().toString();
    }
}
