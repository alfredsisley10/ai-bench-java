package com.omnibank.integration.api;

import com.omnibank.payments.api.PaymentRequest;

public interface WireRailAdapter {

    String submitToFedwire(PaymentRequest request);

    FedwireAckStatus checkStatus(String fedwireImad);

    enum FedwireAckStatus { PENDING, ACCEPTED, REJECTED }
}
