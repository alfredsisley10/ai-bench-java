package com.omnibank.customerportal;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.payments.api.PaymentService;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public SubmitResponse submit(@RequestBody SubmitRequest req) {
        PaymentRequest pr = new PaymentRequest(
                req.idempotencyKey,
                req.rail,
                AccountNumber.of(req.originator),
                Optional.ofNullable(req.beneficiaryRouting).map(RoutingNumber::of),
                req.beneficiaryAccount,
                req.beneficiaryName,
                Money.of(req.amount, req.currency),
                req.memo,
                Instant.now()
        );
        PaymentId id = service.submit(pr);
        return new SubmitResponse(id.value(), service.status(id).name());
    }

    @GetMapping("/{id}")
    public StatusResponse status(@PathVariable UUID id) {
        PaymentStatus status = service.status(new PaymentId(id));
        return new StatusResponse(id, status.name());
    }

    public record SubmitRequest(
            String idempotencyKey,
            PaymentRail rail,
            String originator,
            String beneficiaryRouting,
            String beneficiaryAccount,
            String beneficiaryName,
            BigDecimal amount,
            CurrencyCode currency,
            String memo
    ) {}

    public record SubmitResponse(UUID paymentId, String status) {}

    public record StatusResponse(UUID paymentId, String status) {}
}
