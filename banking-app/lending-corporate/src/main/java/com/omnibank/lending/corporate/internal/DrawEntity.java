package com.omnibank.lending.corporate.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "loan_draw", schema = "lending_corporate")
public class DrawEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "draw_date", nullable = false)
    private LocalDate drawDate;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "purpose", length = 256)
    private String purpose;

    protected DrawEntity() {}

    public DrawEntity(UUID id, UUID loanId, LocalDate drawDate, BigDecimal amount,
                      CurrencyCode currency, String purpose) {
        this.id = id;
        this.loanId = loanId;
        this.drawDate = drawDate;
        this.amount = amount;
        this.currency = currency;
        this.purpose = purpose;
    }

    public UUID id() { return id; }
    public UUID loanId() { return loanId; }
    public LocalDate drawDate() { return drawDate; }
    public BigDecimal amount() { return amount; }
    public CurrencyCode currency() { return currency; }
    public String purpose() { return purpose; }
}
