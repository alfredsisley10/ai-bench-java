package com.omnibank.payments.api;

public interface PaymentService {

    PaymentId submit(PaymentRequest request);

    PaymentStatus status(PaymentId payment);

    void cancel(PaymentId payment, String reason);
}
