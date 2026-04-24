package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "consumer_account", schema = "accounts_consumer")
public class ConsumerAccountEntity extends AuditableEntity {

    @Id
    @Column(name = "account_number", length = 20)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product", nullable = false, length = 32)
    private ConsumerProduct product;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Column(name = "matures_on")
    private LocalDate maturesOn;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "freeze_reason", length = 256)
    private String freezeReason;

    protected ConsumerAccountEntity() {}

    public ConsumerAccountEntity(String accountNumber, UUID customerId, ConsumerProduct product,
                                 CurrencyCode currency, LocalDate openedOn, LocalDate maturesOn) {
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.product = product;
        this.currency = currency;
        this.openedOn = openedOn;
        this.maturesOn = maturesOn;
        this.status = AccountStatus.PENDING;
    }

    public void activate() { this.status = AccountStatus.OPEN; }

    public void freeze(String reason) {
        this.status = AccountStatus.FROZEN;
        this.freezeReason = reason;
    }

    public void unfreeze() {
        if (this.status != AccountStatus.FROZEN) {
            throw new IllegalStateException("Account not frozen: " + accountNumber);
        }
        this.status = AccountStatus.OPEN;
        this.freezeReason = null;
    }

    public void markDormant(String reason) {
        this.status = AccountStatus.DORMANT;
        this.freezeReason = reason;
    }

    public void close(Instant when) {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Account already closed: " + accountNumber);
        }
        this.status = AccountStatus.CLOSED;
        this.closedAt = when;
    }

    public String accountNumber() { return accountNumber; }
    public UUID customerId() { return customerId; }
    public ConsumerProduct product() { return product; }
    public CurrencyCode currency() { return currency; }
    public AccountStatus status() { return status; }
    public LocalDate openedOn() { return openedOn; }
    public LocalDate maturesOn() { return maturesOn; }
    public String freezeReason() { return freezeReason; }
}
