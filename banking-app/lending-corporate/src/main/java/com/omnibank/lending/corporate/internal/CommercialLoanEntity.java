package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.LoanStatus;
import com.omnibank.lending.corporate.api.LoanStructure;
import com.omnibank.lending.corporate.api.PaymentFrequency;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.DayCountConvention;
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
@Table(name = "commercial_loan", schema = "lending_corporate")
public class CommercialLoanEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "borrower", nullable = false)
    private UUID borrower;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LoanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "structure", nullable = false, length = 24)
    private LoanStructure structure;

    @Column(name = "principal_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal principalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "rate_bps", nullable = false)
    private long rateBps;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_count", nullable = false, length = 16)
    private DayCountConvention dayCount;

    @Column(name = "tenor_spec", nullable = false, length = 12)
    private String tenorSpec;

    @Column(name = "origination_date", nullable = false)
    private LocalDate originationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_frequency", nullable = false, length = 12)
    private PaymentFrequency paymentFrequency;

    @Column(name = "outstanding_principal", precision = 19, scale = 4, nullable = false)
    private BigDecimal outstandingPrincipal;

    @Column(name = "total_drawn", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalDrawn;

    @Column(name = "total_repaid", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalRepaid;

    protected CommercialLoanEntity() {}

    public CommercialLoanEntity(UUID id, UUID borrower, LoanStructure structure,
                                BigDecimal principal, CurrencyCode currency, long rateBps,
                                DayCountConvention dayCount, String tenorSpec,
                                LocalDate originationDate, PaymentFrequency paymentFrequency) {
        this.id = id;
        this.borrower = borrower;
        this.status = LoanStatus.APPLICATION;
        this.structure = structure;
        this.principalAmount = principal;
        this.currency = currency;
        this.rateBps = rateBps;
        this.dayCount = dayCount;
        this.tenorSpec = tenorSpec;
        this.originationDate = originationDate;
        this.paymentFrequency = paymentFrequency;
        this.outstandingPrincipal = BigDecimal.ZERO;
        this.totalDrawn = BigDecimal.ZERO;
        this.totalRepaid = BigDecimal.ZERO;
    }

    public void transitionTo(LoanStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal loan transition: " + status + " → " + next);
        }
        this.status = next;
    }

    public void recordDraw(BigDecimal amount) {
        this.totalDrawn = this.totalDrawn.add(amount);
        this.outstandingPrincipal = this.outstandingPrincipal.add(amount);
    }

    public void recordRepayment(BigDecimal amount) {
        this.totalRepaid = this.totalRepaid.add(amount);
        this.outstandingPrincipal = this.outstandingPrincipal.subtract(amount);
    }

    public UUID id() { return id; }
    public UUID borrower() { return borrower; }
    public LoanStatus status() { return status; }
    public LoanStructure structure() { return structure; }
    public BigDecimal principalAmount() { return principalAmount; }
    public CurrencyCode currency() { return currency; }
    public long rateBps() { return rateBps; }
    public DayCountConvention dayCount() { return dayCount; }
    public String tenorSpec() { return tenorSpec; }
    public LocalDate originationDate() { return originationDate; }
    public PaymentFrequency paymentFrequency() { return paymentFrequency; }
    public BigDecimal outstandingPrincipal() { return outstandingPrincipal; }
    public BigDecimal totalDrawn() { return totalDrawn; }
    public BigDecimal totalRepaid() { return totalRepaid; }
}
