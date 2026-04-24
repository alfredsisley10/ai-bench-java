package com.omnibank.payments.internal;

import com.omnibank.payments.ach.AchCutoffPolicy;
import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.payments.api.PaymentService;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.payments.wire.WireCutoffPolicy;
import com.omnibank.shared.domain.Timestamp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository payments;
    private final AchCutoffPolicy achCutoff;
    private final WireCutoffPolicy wireCutoff;
    private final Clock clock;

    public PaymentServiceImpl(PaymentRepository payments,
                              AchCutoffPolicy achCutoff,
                              WireCutoffPolicy wireCutoff,
                              Clock clock) {
        this.payments = payments;
        this.achCutoff = achCutoff;
        this.wireCutoff = wireCutoff;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentId submit(PaymentRequest request) {
        // Idempotency: if already seen, return prior payment id.
        var existing = payments.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return new PaymentId(existing.get().id());
        }

        enforceRailWindow(request.rail(), Timestamp.now(clock));

        PaymentId id = PaymentId.newId();
        PaymentEntity entity = new PaymentEntity(
                id.value(),
                request.idempotencyKey(),
                request.rail(),
                request.originator().raw(),
                request.beneficiaryRouting().map(r -> r.raw()).orElse(null),
                request.beneficiaryAccount(),
                request.beneficiaryName(),
                request.amount().amount(),
                request.amount().currency(),
                request.requestedAt(),
                request.memo()
        );
        entity.validate();
        entity.submit(Timestamp.now(clock));
        payments.save(entity);
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentStatus status(PaymentId payment) {
        return payments.findById(payment.value())
                .map(PaymentEntity::status)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment: " + payment));
    }

    @Override
    @Transactional
    public void cancel(PaymentId payment, String reason) {
        PaymentEntity e = payments.findById(payment.value()).orElseThrow(() ->
                new IllegalArgumentException("Unknown payment: " + payment));
        if (e.status() != PaymentStatus.RECEIVED && e.status() != PaymentStatus.VALIDATED) {
            throw new IllegalStateException("Cannot cancel payment in status: " + e.status());
        }
        e.cancel(reason);
    }

    private void enforceRailWindow(PaymentRail rail, Instant now) {
        switch (rail) {
            case ACH -> {
                if (!achCutoff.isBeforeFinalSameDayCutoff(now)) {
                    // Queue to next business day is allowed here; policy decision.
                    // Today's policy: accept but flag for T+1 settlement.
                }
            }
            case WIRE -> {
                if (!wireCutoff.isFedwireOpen(now)) {
                    throw new IllegalStateException("Wire submitted outside Fedwire customer window");
                }
            }
            case RTP, FEDNOW, BOOK -> {
                // 24/7 — no window enforcement
            }
        }
    }
}
