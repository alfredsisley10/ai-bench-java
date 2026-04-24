package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.SyndicationService.ParticipantRole;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity for syndication participants. Each record represents a single
 * lender's participation in a syndicated loan facility. Tracks commitment
 * shares, funded amounts, settlement instructions, and voting rights.
 *
 * <p>Shares are stored as exact decimal fractions (e.g., 0.25 = 25%).
 * All monetary amounts are in the facility's base currency. Funded amounts
 * are updated with each drawdown and repayment.
 */
@Entity
@Table(name = "syndication_participant", schema = "lending_corporate")
public class SyndicationParticipantEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ParticipantRole role;

    @Column(name = "share_pct", precision = 12, scale = 10, nullable = false)
    private BigDecimal sharePercent;

    @Column(name = "commitment_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal commitmentAmount;

    @Column(name = "funded_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal fundedAmount;

    @Column(name = "unfunded_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal unfundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    // ── Settlement instructions ───────────────────────────────────────────

    @Column(name = "settlement_bank_name", nullable = false, length = 128)
    private String settlementBankName;

    @Column(name = "settlement_routing_number", length = 20)
    private String settlementRoutingNumber;

    @Column(name = "settlement_account_number", nullable = false, length = 40)
    private String settlementAccountNumber;

    @Column(name = "settlement_swift_code", length = 11)
    private String settlementSwiftCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_currency", nullable = false, length = 3)
    private CurrencyCode settlementCurrency;

    // ── Voting and status ─────────────────────────────────────────────────

    @Column(name = "has_voting_rights", nullable = false)
    private boolean hasVotingRights;

    @Column(name = "voting_weight", precision = 12, scale = 10)
    private BigDecimal votingWeight;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "transfer_restriction_end_date")
    private LocalDate transferRestrictionEndDate;

    @Column(name = "minimum_hold_amount", precision = 19, scale = 4)
    private BigDecimal minimumHoldAmount;

    @Column(name = "contact_name", length = 128)
    private String contactName;

    @Column(name = "contact_email", length = 256)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    protected SyndicationParticipantEntity() {}

    public SyndicationParticipantEntity(UUID id, UUID loanId, UUID partyId,
                                        ParticipantRole role, BigDecimal sharePercent,
                                        BigDecimal commitmentAmount, CurrencyCode currency,
                                        String settlementBankName, String settlementAccountNumber,
                                        CurrencyCode settlementCurrency,
                                        LocalDate effectiveDate) {
        this.id = id;
        this.loanId = loanId;
        this.partyId = partyId;
        this.role = role;
        this.sharePercent = sharePercent;
        this.commitmentAmount = commitmentAmount;
        this.fundedAmount = BigDecimal.ZERO;
        this.unfundedAmount = commitmentAmount;
        this.currency = currency;
        this.settlementBankName = settlementBankName;
        this.settlementAccountNumber = settlementAccountNumber;
        this.settlementCurrency = settlementCurrency;
        this.hasVotingRights = role != ParticipantRole.SUB_PARTICIPANT;
        this.votingWeight = sharePercent;
        this.active = true;
        this.effectiveDate = effectiveDate;
    }

    // ── Business methods ──────────────────────────────────────────────────

    public void recordDrawdownShare(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Drawdown amount must be positive");
        }
        this.fundedAmount = this.fundedAmount.add(amount);
        this.unfundedAmount = this.commitmentAmount.subtract(this.fundedAmount);
        if (this.unfundedAmount.signum() < 0) {
            throw new IllegalStateException(
                    "Funded amount %s exceeds commitment %s for participant %s"
                            .formatted(this.fundedAmount, this.commitmentAmount, this.partyId));
        }
    }

    public void recordRepaymentShare(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Repayment amount must be positive");
        }
        this.fundedAmount = this.fundedAmount.subtract(amount);
        this.unfundedAmount = this.commitmentAmount.subtract(this.fundedAmount);
        if (this.fundedAmount.signum() < 0) {
            throw new IllegalStateException(
                    "Funded amount went negative for participant %s".formatted(this.partyId));
        }
    }

    public void adjustShare(BigDecimal newSharePercent, BigDecimal newCommitmentAmount) {
        Objects.requireNonNull(newSharePercent, "newSharePercent");
        Objects.requireNonNull(newCommitmentAmount, "newCommitmentAmount");
        if (newSharePercent.signum() <= 0 || newSharePercent.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Share must be in (0, 1]: " + newSharePercent);
        }
        if (newCommitmentAmount.compareTo(this.fundedAmount) < 0) {
            throw new IllegalArgumentException(
                    "New commitment %s is less than funded amount %s"
                            .formatted(newCommitmentAmount, this.fundedAmount));
        }
        this.sharePercent = newSharePercent;
        this.commitmentAmount = newCommitmentAmount;
        this.unfundedAmount = newCommitmentAmount.subtract(this.fundedAmount);
        if (this.hasVotingRights) {
            this.votingWeight = newSharePercent;
        }
    }

    public void terminate(LocalDate terminationDate) {
        if (this.fundedAmount.signum() > 0) {
            throw new IllegalStateException(
                    "Cannot terminate participant %s with outstanding funded amount %s"
                            .formatted(this.partyId, this.fundedAmount));
        }
        this.active = false;
        this.terminationDate = terminationDate;
    }

    public boolean canTransfer(LocalDate asOf) {
        if (!active) return false;
        if (transferRestrictionEndDate != null && !asOf.isAfter(transferRestrictionEndDate)) {
            return false;
        }
        if (minimumHoldAmount != null && commitmentAmount.compareTo(minimumHoldAmount) <= 0) {
            return false;
        }
        return true;
    }

    public void updateSettlementInstructions(String bankName, String routingNumber,
                                              String accountNumber, String swiftCode,
                                              CurrencyCode settlementCurrency) {
        this.settlementBankName = Objects.requireNonNull(bankName, "bankName");
        this.settlementRoutingNumber = routingNumber;
        this.settlementAccountNumber = Objects.requireNonNull(accountNumber, "accountNumber");
        this.settlementSwiftCode = swiftCode;
        this.settlementCurrency = Objects.requireNonNull(settlementCurrency, "settlementCurrency");
    }

    public void setContactInfo(String name, String email, String phone) {
        this.contactName = name;
        this.contactEmail = email;
        this.contactPhone = phone;
    }

    public void setTransferRestriction(LocalDate restrictionEndDate, BigDecimal minimumHold) {
        this.transferRestrictionEndDate = restrictionEndDate;
        this.minimumHoldAmount = minimumHold;
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public UUID id() { return id; }
    public UUID loanId() { return loanId; }
    public UUID partyId() { return partyId; }
    public ParticipantRole role() { return role; }
    public BigDecimal sharePercent() { return sharePercent; }
    public BigDecimal commitmentAmount() { return commitmentAmount; }
    public BigDecimal fundedAmount() { return fundedAmount; }
    public BigDecimal unfundedAmount() { return unfundedAmount; }
    public CurrencyCode currency() { return currency; }
    public String settlementBankName() { return settlementBankName; }
    public String settlementRoutingNumber() { return settlementRoutingNumber; }
    public String settlementAccountNumber() { return settlementAccountNumber; }
    public String settlementSwiftCode() { return settlementSwiftCode; }
    public CurrencyCode settlementCurrency() { return settlementCurrency; }
    public boolean hasVotingRights() { return hasVotingRights; }
    public BigDecimal votingWeight() { return votingWeight; }
    public boolean isActive() { return active; }
    public LocalDate effectiveDate() { return effectiveDate; }
    public LocalDate terminationDate() { return terminationDate; }
    public String contactName() { return contactName; }
    public String contactEmail() { return contactEmail; }
}
