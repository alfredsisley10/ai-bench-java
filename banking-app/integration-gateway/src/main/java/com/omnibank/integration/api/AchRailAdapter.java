package com.omnibank.integration.api;

import com.omnibank.payments.api.PaymentRequest;

import java.time.LocalDate;

public interface AchRailAdapter {

    String submitBatch(PaymentRequest request, LocalDate settlementDate);

    void acknowledgeReturn(String traceNumber, String returnCode);
}
