package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;

/**
 * Balance trio used throughout the UI and downstream modules:
 *   - ledger:    the GL truth (posted only)
 *   - available: ledger minus holds (what the customer can actually spend)
 *   - pending:   holds + memo-posts still in flight
 */
public record BalanceView(AccountNumber account, Money ledger, Money available, Money pending) {
}
