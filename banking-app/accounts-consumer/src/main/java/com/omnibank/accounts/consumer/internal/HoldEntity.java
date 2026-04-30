package com.omnibank.accounts.consumer.internal;

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
@Table(name = "hold", schema = "accounts_consumer")
public class HoldEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "reason", length = 256)
    private String reason;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected HoldEntity() {}

    public HoldEntity(UUID id, String accountNumber, BigDecimal amount, CurrencyCode currency,
                      String reason, Instant placedAt, Instant expiresAt) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.placedAt = placedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) {
        return releasedAt == null && !now.isAfter(expiresAt);
    }

    public void release(Instant when) {
        this.releasedAt = when;
    }

    public UUID id() { return id; }
    public String accountNumber() { return accountNumber; }
    public BigDecimal amount() { return amount; }
    public CurrencyCode currency() { return currency; }
    public String reason() { return reason; }
    public Instant placedAt() { return placedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant releasedAt() { return releasedAt; }
}
