package com.omnibank.payments.internal;

import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment", schema = "payments_hub")
public class PaymentEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 10)
    private PaymentRail rail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "originator_account", nullable = false, length = 20)
    private String originatorAccount;

    @Column(name = "beneficiary_routing", length = 9)
    private String beneficiaryRouting;

    @Column(name = "beneficiary_account", nullable = false, length = 34)
    private String beneficiaryAccount;

    @Column(name = "beneficiary_name", nullable = false, length = 128)
    private String beneficiaryName;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "memo", length = 256)
    private String memo;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    protected PaymentEntity() {}

    public PaymentEntity(UUID id, String idempotencyKey, PaymentRail rail, String originatorAccount,
                         String beneficiaryRouting, String beneficiaryAccount, String beneficiaryName,
                         BigDecimal amount, CurrencyCode currency, Instant requestedAt, String memo) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.rail = rail;
        this.status = PaymentStatus.RECEIVED;
        this.originatorAccount = originatorAccount;
        this.beneficiaryRouting = beneficiaryRouting;
        this.beneficiaryAccount = beneficiaryAccount;
        this.beneficiaryName = beneficiaryName;
        this.amount = amount;
        this.currency = currency;
        this.requestedAt = requestedAt;
        this.memo = memo;
    }

    public void validate() { this.status = PaymentStatus.VALIDATED; }
    public void submit(Instant when) { this.status = PaymentStatus.SUBMITTED; this.submittedAt = when; }
    public void settle(Instant when) { this.status = PaymentStatus.SETTLED; this.settledAt = when; }
    public void reject(String reason) { this.status = PaymentStatus.REJECTED; this.failureReason = reason; }
    public void markReturned(String reason) { this.status = PaymentStatus.RETURNED; this.failureReason = reason; }
    public void cancel(String reason) { this.status = PaymentStatus.CANCELED; this.failureReason = reason; }

    public UUID id() { return id; }
    public String idempotencyKey() { return idempotencyKey; }
    public PaymentRail rail() { return rail; }
    public PaymentStatus status() { return status; }
    public BigDecimal amount() { return amount; }
    public CurrencyCode currency() { return currency; }
}
