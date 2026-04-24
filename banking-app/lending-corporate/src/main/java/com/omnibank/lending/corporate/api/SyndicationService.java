package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.PartyId;
import com.omnibank.shared.domain.Percent;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages syndicated loan participation. A syndicated loan is originated by a
 * lead arranger who then sells down participations to other lenders. This service
 * handles the full lifecycle: structuring the syndicate, distributing drawdowns
 * pro-rata, allocating repayments, and splitting fees among participants.
 *
 * <p>All share calculations use exact arithmetic. The lead arranger absorbs any
 * rounding remainder so the deal always balances to the penny.
 */
public interface SyndicationService {

    // ── Value types ───────────────────────────────────────────────────────

    enum ParticipantRole { LEAD_ARRANGER, CO_ARRANGER, PARTICIPANT, SUB_PARTICIPANT }

    record ParticipantDetail(
            PartyId party,
            ParticipantRole role,
            BigDecimal share,
            Money commitment,
            Money funded,
            Money unfunded,
            SettlementInstruction settlement
    ) {
        public ParticipantDetail {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(share, "share");
            Objects.requireNonNull(commitment, "commitment");
            Objects.requireNonNull(funded, "funded");
            Objects.requireNonNull(unfunded, "unfunded");
            Objects.requireNonNull(settlement, "settlement");
            if (share.signum() <= 0 || share.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Share must be in (0, 1]: " + share);
            }
        }
    }

    record SettlementInstruction(
            String bankName,
            String routingNumber,
            String accountNumber,
            String swiftCode,
            CurrencyCode currency
    ) {
        public SettlementInstruction {
            Objects.requireNonNull(bankName, "bankName");
            Objects.requireNonNull(accountNumber, "accountNumber");
            Objects.requireNonNull(currency, "currency");
        }
    }

    record DrawdownAllocation(
            PartyId party,
            Money amount,
            BigDecimal sharePercent
    ) {
        public DrawdownAllocation {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(sharePercent, "sharePercent");
        }
    }

    record RepaymentAllocation(
            PartyId party,
            Money principalShare,
            Money interestShare
    ) {
        public RepaymentAllocation {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(principalShare, "principalShare");
            Objects.requireNonNull(interestShare, "interestShare");
        }
    }

    record FeeDistribution(
            String feeType,
            Money totalAmount,
            List<ParticipantFeeShare> shares
    ) {
        public FeeDistribution {
            Objects.requireNonNull(feeType, "feeType");
            Objects.requireNonNull(totalAmount, "totalAmount");
            shares = List.copyOf(shares);
        }
    }

    record ParticipantFeeShare(PartyId party, Money amount) {
        public ParticipantFeeShare {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(amount, "amount");
        }
    }

    record SyndicateStructure(
            LoanId loanId,
            PartyId leadArranger,
            Money totalCommitment,
            List<ParticipantDetail> participants,
            LocalDate closingDate,
            boolean fullySyndicated
    ) {
        public SyndicateStructure {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(leadArranger, "leadArranger");
            Objects.requireNonNull(totalCommitment, "totalCommitment");
            participants = List.copyOf(participants);
            Objects.requireNonNull(closingDate, "closingDate");
        }

        public BigDecimal totalSharePercent() {
            return participants.stream()
                    .map(ParticipantDetail::share)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    // ── Operations ────────────────────────────────────────────────────────

    /**
     * Structures a new syndicate for a loan. Validates that shares sum to 1.0
     * and that the lead arranger retains the required minimum hold.
     */
    SyndicateStructure structureSyndicate(
            LoanId loanId,
            PartyId leadArranger,
            Money totalCommitment,
            List<SyndicationParticipant> participants,
            LocalDate closingDate
    );

    /**
     * Adds a new participant to an existing syndicate via assignment or
     * sub-participation. Recalculates shares for existing participants.
     */
    SyndicateStructure addParticipant(
            LoanId loanId,
            SyndicationParticipant newParticipant,
            ParticipantRole role,
            SettlementInstruction settlement
    );

    /**
     * Distributes a drawdown amount pro-rata across syndicate participants.
     * The lead arranger absorbs rounding remainders.
     */
    List<DrawdownAllocation> distributeDrawdown(LoanId loanId, Money drawdownAmount);

    /**
     * Allocates a repayment (principal + interest) across participants
     * according to their funded shares.
     */
    List<RepaymentAllocation> allocateRepayment(
            LoanId loanId,
            Money principalRepayment,
            Money interestPayment
    );

    /**
     * Distributes fees across participants. Arrangement fees may be split
     * differently (lead gets a larger share) while commitment fees follow
     * pro-rata shares.
     */
    FeeDistribution distributeFee(LoanId loanId, String feeType, Money feeAmount);

    /**
     * Returns the current syndicate structure for a loan.
     */
    Optional<SyndicateStructure> getSyndicate(LoanId loanId);

    // ── Utility calculations ──────────────────────────────────────────────

    /**
     * Calculates pro-rata allocations for a given amount across shares.
     * Uses exact arithmetic; the first participant (typically the lead) absorbs
     * the rounding remainder so allocations always sum exactly to the total.
     */
    static List<DrawdownAllocation> calculateProRataShares(
            Money totalAmount,
            List<SyndicationParticipant> participants
    ) {
        MathContext mc = new MathContext(20, RoundingMode.HALF_EVEN);
        List<DrawdownAllocation> allocations = new ArrayList<>();
        Money allocated = Money.zero(totalAmount.currency());

        for (int i = 0; i < participants.size(); i++) {
            SyndicationParticipant p = participants.get(i);
            Money share;
            if (i == participants.size() - 1) {
                // Last participant gets the remainder to avoid rounding drift
                share = totalAmount.minus(allocated);
            } else {
                share = totalAmount.times(p.share());
                allocated = allocated.plus(share);
            }
            allocations.add(new DrawdownAllocation(p.party(), share, p.share()));
        }
        return Collections.unmodifiableList(allocations);
    }

    /**
     * Validates that participant shares sum to exactly 1.0 (100%).
     */
    static boolean validateSharesSum(List<SyndicationParticipant> participants) {
        BigDecimal total = participants.stream()
                .map(SyndicationParticipant::share)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.compareTo(BigDecimal.ONE) == 0;
    }

    /**
     * Calculates the minimum hold requirement for the lead arranger.
     * Regulatory guidance typically requires the lead to retain at least 10-20%
     * of the facility to ensure alignment of interests.
     */
    static Money minimumLeadHold(Money totalCommitment, BigDecimal minimumRetention) {
        return totalCommitment.times(minimumRetention);
    }

    /**
     * Computes commitment fee for each participant based on their unfunded
     * commitment and the applicable rate.
     */
    static Map<PartyId, Money> computeCommitmentFees(
            List<ParticipantDetail> participants,
            Percent annualRate,
            int daysInPeriod,
            int daysInYear
    ) {
        MathContext mc = new MathContext(20, RoundingMode.HALF_EVEN);
        BigDecimal dayFraction = BigDecimal.valueOf(daysInPeriod)
                .divide(BigDecimal.valueOf(daysInYear), mc);
        BigDecimal rateFraction = annualRate.asFraction(mc);

        Map<PartyId, Money> fees = new HashMap<>();
        for (var participant : participants) {
            Money fee = participant.unfunded()
                    .times(rateFraction.multiply(dayFraction, mc));
            fees.put(participant.party(), fee);
        }
        return Collections.unmodifiableMap(fees);
    }

    /**
     * Determines voting power for syndicate decisions. Typically proportional
     * to commitment share, with certain decisions requiring supermajority (66.67%)
     * or unanimous consent.
     */
    static BigDecimal computeVotingPower(
            List<ParticipantDetail> participants,
            List<PartyId> votingInFavor
    ) {
        MathContext mc = new MathContext(10, RoundingMode.HALF_EVEN);
        BigDecimal totalVotingShare = BigDecimal.ZERO;
        for (var p : participants) {
            if (votingInFavor.contains(p.party())) {
                totalVotingShare = totalVotingShare.add(p.share());
            }
        }
        return totalVotingShare;
    }
}
