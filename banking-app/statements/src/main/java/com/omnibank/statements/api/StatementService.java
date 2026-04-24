package com.omnibank.statements.api;

import com.omnibank.shared.domain.AccountNumber;

import java.time.LocalDate;

public interface StatementService {

    StatementDocument generate(AccountNumber account, LocalDate cycleStart, LocalDate cycleEnd);

    byte[] render1099Int(AccountNumber account, int taxYear);
}
