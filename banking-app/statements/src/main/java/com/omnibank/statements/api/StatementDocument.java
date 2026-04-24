package com.omnibank.statements.api;

import com.omnibank.shared.domain.AccountNumber;

import java.time.LocalDate;

public record StatementDocument(
        AccountNumber account,
        LocalDate cycleStart,
        LocalDate cycleEnd,
        byte[] pdf
) {}
