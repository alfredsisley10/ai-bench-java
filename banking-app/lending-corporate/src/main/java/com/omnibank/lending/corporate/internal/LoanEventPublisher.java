package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.CovenantEngine.BreachSeverity;
import com.omnibank.lending.corporate.api.LoanId;
import com.omnibank.lending.corporate.api.LoanRatingModel.RatingGrade;
import com.omnibank.lending.corporate.api.LoanStatus;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Publishes domain events for loan lifecycle transitions. All significant state
 * changes produce events for downstream consumers: risk systems, accounting,
 * regulatory reporting, data warehouse, and operational dashboards.
 *
 * <p>Events follow the dotted naming convention: {@code "lending.corporate.*"}.
 * Each event is immutable and self-describing with all data needed for
 * downstream processing (no back-references needed).
 */
public class LoanEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoanEventPublisher.class);

    private final EventBus eventBus;

    public LoanEventPublisher(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    // ── Event definitions ─────────────────────────────────────────────────

    public record LoanOriginatedEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            UUID borrowerId,
            Money principal,
            String structure,
            LocalDate originationDate
    ) implements DomainEvent {
        @Override public String eventType() { return "lending.corporate.originated"; }
    }

    public record LoanStatusChangedEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            LoanStatus previousStatus,
            LoanStatus newStatus,
            String changedBy,
            Optional<String> reason
    ) implements DomainEvent {
        public LoanStatusChangedEvent {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
            Objects.requireNonNull(loanId);
            Objects.requireNonNull(previousStatus);
            Objects.requireNonNull(newStatus);
            Objects.requireNonNull(reason);
        }
        @Override public String eventType() { return "lending.corporate.status_changed"; }
    }

    public record DrawdownEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            UUID facilityId,
            Money drawAmount,
            Money newOutstanding,
            String purpose,
            LocalDate valueDate
    ) implements DomainEvent {
        @Override public String eventType() { return "lending.corporate.drawdown"; }
    }

    public record RepaymentEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            Money principalRepaid,
            Money interestPaid,
            Money feesPaid,
            Money newOutstanding,
            LocalDate effectiveDate,
            boolean isPayoff
    ) implements DomainEvent {
        @Override public String eventType() { return "lending.corporate.repayment"; }
    }

    public record CovenantBreachEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            UUID covenantId,
            String covenantDescription,
            BreachSeverity severity,
            BigDecimal actualValue,
            BigDecimal thresholdValue,
            Optional<LocalDate> cureDeadline
    ) implements DomainEvent {
        public CovenantBreachEvent {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
            Objects.requireNonNull(loanId);
            Objects.requireNonNull(covenantId);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(cureDeadline);
        }
        @Override public String eventType() { return "lending.corporate.covenant_breach"; }
    }

    public record RatingChangedEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            RatingGrade previousRating,
            RatingGrade newRating,
            int notchesChanged,
            String trigger,
            BigDecimal newPd
    ) implements DomainEvent {
        public RatingChangedEvent {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
            Objects.requireNonNull(loanId);
            Objects.requireNonNull(previousRating);
            Objects.requireNonNull(newRating);
        }
        @Override public String eventType() { return "lending.corporate.rating_changed"; }
    }

    public record FacilityAmendedEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            UUID facilityId,
            String amendmentType,
            String approvedBy,
            Money previousCommitment,
            Money newCommitment
    ) implements DomainEvent {
        @Override public String eventType() { return "lending.corporate.facility_amended"; }
    }

    public record MarginCallEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            Money shortfall,
            BigDecimal currentLtv,
            BigDecimal requiredLtv,
            LocalDate responseDeadline
    ) implements DomainEvent {
        @Override public String eventType() { return "lending.corporate.margin_call"; }
    }

    public record PrepaymentEvent(
            UUID eventId,
            Instant occurredAt,
            LoanId loanId,
            Money prepaymentAmount,
            Money penaltyAmount,
            Money breakageCost,
            Money newOutstanding,
            LocalDate effectiveDate
    ) implements DomainEvent {
        @Override public String eventType() { return "lending.corporate.prepayment"; }
    }

    // ── Publishing methods ────────────────────────────────────────────────

    public void publishLoanOriginated(LoanId loanId, UUID borrowerId,
                                       Money principal, String structure,
                                       LocalDate originationDate) {
        var event = new LoanOriginatedEvent(
                UUID.randomUUID(), Instant.now(), loanId, borrowerId,
                principal, structure, originationDate
        );
        publish(event);
    }

    public void publishStatusChanged(LoanId loanId, LoanStatus previousStatus,
                                      LoanStatus newStatus, String changedBy,
                                      Optional<String> reason) {
        var event = new LoanStatusChangedEvent(
                UUID.randomUUID(), Instant.now(), loanId,
                previousStatus, newStatus, changedBy, reason
        );
        publish(event);
    }

    public void publishDrawdown(LoanId loanId, UUID facilityId,
                                 Money drawAmount, Money newOutstanding,
                                 String purpose, LocalDate valueDate) {
        var event = new DrawdownEvent(
                UUID.randomUUID(), Instant.now(), loanId, facilityId,
                drawAmount, newOutstanding, purpose, valueDate
        );
        publish(event);
    }

    public void publishRepayment(LoanId loanId, Money principalRepaid,
                                  Money interestPaid, Money feesPaid,
                                  Money newOutstanding, LocalDate effectiveDate,
                                  boolean isPayoff) {
        var event = new RepaymentEvent(
                UUID.randomUUID(), Instant.now(), loanId,
                principalRepaid, interestPaid, feesPaid,
                newOutstanding, effectiveDate, isPayoff
        );
        publish(event);
    }

    public void publishCovenantBreach(LoanId loanId, UUID covenantId,
                                       String covenantDescription,
                                       BreachSeverity severity,
                                       BigDecimal actualValue,
                                       BigDecimal thresholdValue,
                                       Optional<LocalDate> cureDeadline) {
        var event = new CovenantBreachEvent(
                UUID.randomUUID(), Instant.now(), loanId, covenantId,
                covenantDescription, severity, actualValue, thresholdValue,
                cureDeadline
        );
        publish(event);
    }

    public void publishRatingChanged(LoanId loanId,
                                      RatingGrade previousRating,
                                      RatingGrade newRating,
                                      int notchesChanged,
                                      String trigger,
                                      BigDecimal newPd) {
        var event = new RatingChangedEvent(
                UUID.randomUUID(), Instant.now(), loanId,
                previousRating, newRating, notchesChanged, trigger, newPd
        );
        publish(event);
    }

    public void publishFacilityAmended(LoanId loanId, UUID facilityId,
                                        String amendmentType, String approvedBy,
                                        Money previousCommitment,
                                        Money newCommitment) {
        var event = new FacilityAmendedEvent(
                UUID.randomUUID(), Instant.now(), loanId, facilityId,
                amendmentType, approvedBy, previousCommitment, newCommitment
        );
        publish(event);
    }

    public void publishMarginCall(LoanId loanId, Money shortfall,
                                   BigDecimal currentLtv, BigDecimal requiredLtv,
                                   LocalDate responseDeadline) {
        var event = new MarginCallEvent(
                UUID.randomUUID(), Instant.now(), loanId,
                shortfall, currentLtv, requiredLtv, responseDeadline
        );
        publish(event);
    }

    public void publishPrepayment(LoanId loanId, Money prepaymentAmount,
                                   Money penaltyAmount, Money breakageCost,
                                   Money newOutstanding, LocalDate effectiveDate) {
        var event = new PrepaymentEvent(
                UUID.randomUUID(), Instant.now(), loanId,
                prepaymentAmount, penaltyAmount, breakageCost,
                newOutstanding, effectiveDate
        );
        publish(event);
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void publish(DomainEvent event) {
        try {
            eventBus.publish(event);
            log.debug("Published event: type={}, id={}", event.eventType(), event.eventId());
        } catch (Exception e) {
            // Event publishing should never fail the business transaction.
            // Log and continue — downstream retry/dead-letter handles it.
            log.error("Failed to publish event {}: {}", event.eventType(), e.getMessage(), e);
        }
    }
}
