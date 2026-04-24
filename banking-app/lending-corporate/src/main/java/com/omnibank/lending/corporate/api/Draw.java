package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.UUID;

public record Draw(
        UUID id,
        LocalDate drawDate,
        Money amount,
        String purpose
) {}
