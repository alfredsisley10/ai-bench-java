package com.omnibank.payments.batch;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.RoutingNumber;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes ACH return files received from the Federal Reserve or EPN.
 *
 * <p>ACH returns follow a strict timeline:
 * <ul>
 *   <li>Return reason codes R01-R16 must be returned within 2 banking days</li>
 *   <li>Unauthorized returns (R05, R07, R10, R29) have extended 60-day windows</li>
 *   <li>Administrative returns (R11, R12, R13, R14) have specific deadline rules</li>
 * </ul>
 *
 * <p>This processor:
 * <ol>
 *   <li>Parses return entries from NACHA-format return files</li>
 *   <li>Maps return reason codes to business-level actions</li>
 *   <li>Triggers payment status updates and reversal entries</li>
 *   <li>Generates notification events for downstream systems</li>
 * </ol>
 */
public class AchReturnProcessor {

    private static final Logger log = LoggerFactory.getLogger(AchReturnProcessor.class);

    /**
     * NACHA ACH return reason codes.
     */
    public enum ReturnReasonCode {
        R01("Insufficient Funds", ReturnCategory.STANDARD, 2),
        R02("Account Closed", ReturnCategory.STANDARD, 2),
        R03("No Account/Unable to Locate Account", ReturnCategory.STANDARD, 2),
        R04("Invalid Account Number", ReturnCategory.STANDARD, 2),
        R05("Unauthorized Debit to Consumer Account", ReturnCategory.UNAUTHORIZED, 60),
        R06("Returned per ODFI's Request", ReturnCategory.STANDARD, 2),
        R07("Authorization Revoked by Customer", ReturnCategory.UNAUTHORIZED, 60),
        R08("Payment Stopped", ReturnCategory.STANDARD, 2),
        R09("Uncollected Funds", ReturnCategory.STANDARD, 2),
        R10("Customer Advises Unauthorized/Improper", ReturnCategory.UNAUTHORIZED, 60),
        R11("Check Truncation Entry Return", ReturnCategory.ADMINISTRATIVE, 2),
        R12("Branch Sold to Another DFI", ReturnCategory.ADMINISTRATIVE, 2),
        R13("RDFI Not Qualified to Participate", ReturnCategory.ADMINISTRATIVE, 2),
        R14("Representative Payee Deceased", ReturnCategory.ADMINISTRATIVE, 2),
        R15("Beneficiary or Account Holder Deceased", ReturnCategory.STANDARD, 2),
        R16("Account Frozen", ReturnCategory.STANDARD, 2),
        R17("File Record Edit Criteria", ReturnCategory.STANDARD, 2),
        R20("Non-Transaction Account", ReturnCategory.STANDARD, 2),
        R21("Invalid Company ID Number", ReturnCategory.STANDARD, 2),
        R22("Invalid Individual ID Number", ReturnCategory.STANDARD, 2),
        R23("Credit Entry Refused by Receiver", ReturnCategory.STANDARD, 2),
        R24("Duplicate Entry", ReturnCategory.STANDARD, 2),
        R29("Corporate Customer Advises Not Authorized", ReturnCategory.UNAUTHORIZED, 60),
        R31("Permissible Return Entry", ReturnCategory.STANDARD, 2),
        R33("Return of XCK Entry", ReturnCategory.STANDARD, 2);

        private final String description;
        private final ReturnCategory category;
        private final int returnWindowDays;

        ReturnReasonCode(String description, ReturnCategory category, int returnWindowDays) {
            this.description = description;
            this.category = category;
            this.returnWindowDays = returnWindowDays;
        }

        public String description() { return description; }
        public ReturnCategory category() { return category; }
        public int returnWindowDays() { return returnWindowDays; }
    }

    public enum ReturnCategory {
        STANDARD,       // Standard 2-day return window
        UNAUTHORIZED,   // Extended 60-day window for unauthorized transactions
        ADMINISTRATIVE  // Administrative returns with specific rules
    }

    public enum ReturnAction {
        REVERSE_AND_NOTIFY,      // Full reversal, notify originator
        REVERSE_AND_RESUBMIT,    // Reverse, queue for re-origination
        REVERSE_AND_CLOSE,       // Reverse and flag account for closure
        REVERSE_AND_INVESTIGATE, // Reverse and trigger fraud investigation
        NOTIFY_ONLY              // Informational — no reversal needed
    }

    public record AchReturnEntry(
            String traceNumber,
            String originalTraceNumber,
            ReturnReasonCode reasonCode,
            RoutingNumber originatingDfi,
            String dfiAccountNumber,
            BigDecimal originalAmount,
            String individualName,
            LocalDate returnDate,
            String addendaInfo
    ) {
        public AchReturnEntry {
            Objects.requireNonNull(traceNumber, "traceNumber");
            Objects.requireNonNull(originalTraceNumber, "originalTraceNumber");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(originatingDfi, "originatingDfi");
            Objects.requireNonNull(originalAmount, "originalAmount");
            Objects.requireNonNull(returnDate, "returnDate");
        }
    }

    public record ReturnProcessingResult(
            String returnId,
            AchReturnEntry returnEntry,
            PaymentId originalPaymentId,
            ReturnAction action,
            PaymentStatus newPaymentStatus,
            Money reversalAmount,
            Instant processedAt,
            String notes
    ) {}

    public record ReturnFileSummary(
            String fileId,
            int totalReturns,
            int processedSuccessfully,
            int processingErrors,
            Money totalReversalAmount,
            List<ReturnProcessingResult> results,
            Instant processedAt
    ) {}

    private final Clock clock;
    private final Map<String, ReturnProcessingResult> processedReturns = new ConcurrentHashMap<>();
    private final Map<String, PaymentId> traceToPaymentIndex = new ConcurrentHashMap<>();

    public AchReturnProcessor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Processes a complete ACH return file containing multiple return entries.
     */
    public ReturnFileSummary processReturnFile(String fileId, List<AchReturnEntry> entries) {
        log.info("Processing ACH return file: fileId={}, entries={}", fileId, entries.size());
        var now = Timestamp.now(clock);

        var results = new ArrayList<ReturnProcessingResult>();
        int successCount = 0;
        int errorCount = 0;
        var totalReversal = Money.zero(CurrencyCode.USD);

        for (var entry : entries) {
            try {
                var result = processSingleReturn(entry);
                results.add(result);
                if (result.reversalAmount() != null) {
                    totalReversal = totalReversal.plus(result.reversalAmount());
                }
                successCount++;
            } catch (Exception e) {
                log.error("Error processing return entry: trace={}, reason={}",
                        entry.traceNumber(), entry.reasonCode(), e);
                errorCount++;
            }
        }

        var summary = new ReturnFileSummary(
                fileId, entries.size(), successCount, errorCount,
                totalReversal, List.copyOf(results), now);

        log.info("ACH return file processed: fileId={}, success={}, errors={}, totalReversal={}",
                fileId, successCount, errorCount, totalReversal);
        return summary;
    }

    /**
     * Processes a single ACH return entry.
     */
    public ReturnProcessingResult processSingleReturn(AchReturnEntry entry) {
        var now = Timestamp.now(clock);
        var returnId = "ACHRET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        log.info("Processing ACH return: trace={}, reason={} ({}), amount={}",
                entry.originalTraceNumber(), entry.reasonCode(), entry.reasonCode().description(),
                entry.originalAmount());

        // Look up the original payment
        var originalPaymentId = resolveOriginalPayment(entry.originalTraceNumber());

        // Determine action based on return reason
        var action = determineReturnAction(entry.reasonCode());

        // Calculate reversal amount
        var reversalAmount = Money.of(entry.originalAmount(), CurrencyCode.USD);

        // Determine new payment status
        var newStatus = mapToPaymentStatus(entry.reasonCode());

        var notes = buildProcessingNotes(entry, action);

        var result = new ReturnProcessingResult(
                returnId, entry, originalPaymentId, action,
                newStatus, reversalAmount, now, notes);

        processedReturns.put(returnId, result);

        log.info("ACH return processed: id={}, action={}, status={}, reversal={}",
                returnId, action, newStatus, reversalAmount);

        // Trigger downstream actions
        executeReturnAction(result);

        return result;
    }

    /**
     * Registers a mapping from ACH trace number to payment ID for return matching.
     */
    public void registerTraceMapping(String traceNumber, PaymentId paymentId) {
        traceToPaymentIndex.put(traceNumber, paymentId);
    }

    /**
     * Retrieves a processed return result by return ID.
     */
    public Optional<ReturnProcessingResult> getReturn(String returnId) {
        return Optional.ofNullable(processedReturns.get(returnId));
    }

    /**
     * Returns all processed returns for a specific reason code category.
     */
    public List<ReturnProcessingResult> getReturnsByCategory(ReturnCategory category) {
        return processedReturns.values().stream()
                .filter(r -> r.returnEntry().reasonCode().category() == category)
                .toList();
    }

    /**
     * Checks whether a return is within the allowed NACHA return window.
     */
    public boolean isWithinReturnWindow(AchReturnEntry entry, LocalDate originalSettlementDate) {
        int windowDays = entry.reasonCode().returnWindowDays();
        var deadline = originalSettlementDate.plusDays(windowDays);
        return !entry.returnDate().isAfter(deadline);
    }

    private ReturnAction determineReturnAction(ReturnReasonCode reason) {
        return switch (reason) {
            case R01, R09 -> ReturnAction.REVERSE_AND_RESUBMIT;
            case R02, R03, R04, R15 -> ReturnAction.REVERSE_AND_CLOSE;
            case R05, R07, R10, R29 -> ReturnAction.REVERSE_AND_INVESTIGATE;
            case R06 -> ReturnAction.NOTIFY_ONLY;
            case R08, R16, R20, R23 -> ReturnAction.REVERSE_AND_NOTIFY;
            case R11, R12, R13, R14 -> ReturnAction.REVERSE_AND_NOTIFY;
            case R17, R21, R22, R24 -> ReturnAction.REVERSE_AND_NOTIFY;
            case R31, R33 -> ReturnAction.REVERSE_AND_NOTIFY;
        };
    }

    private PaymentStatus mapToPaymentStatus(ReturnReasonCode reason) {
        return switch (reason.category()) {
            case STANDARD, ADMINISTRATIVE -> PaymentStatus.RETURNED;
            case UNAUTHORIZED -> PaymentStatus.RETURNED;
        };
    }

    private PaymentId resolveOriginalPayment(String originalTraceNumber) {
        var paymentId = traceToPaymentIndex.get(originalTraceNumber);
        if (paymentId == null) {
            log.warn("Could not resolve original payment for trace: {}", originalTraceNumber);
            // Return a placeholder — in production, this would query the payment database
            return PaymentId.newId();
        }
        return paymentId;
    }

    private String buildProcessingNotes(AchReturnEntry entry, ReturnAction action) {
        var sb = new StringBuilder();
        sb.append("Return reason: %s (%s). ".formatted(entry.reasonCode(), entry.reasonCode().description()));
        sb.append("Category: %s. ".formatted(entry.reasonCode().category()));
        sb.append("Action: %s. ".formatted(action));
        if (entry.addendaInfo() != null && !entry.addendaInfo().isBlank()) {
            sb.append("Addenda: %s".formatted(entry.addendaInfo().trim()));
        }
        return sb.toString();
    }

    private void executeReturnAction(ReturnProcessingResult result) {
        switch (result.action()) {
            case REVERSE_AND_NOTIFY -> {
                log.debug("Triggering reversal and notification: paymentId={}", result.originalPaymentId());
                // In production: publish reversal event to ledger, notify originator
            }
            case REVERSE_AND_RESUBMIT -> {
                log.debug("Triggering reversal and re-origination queue: paymentId={}", result.originalPaymentId());
                // In production: reverse, then re-queue for next ACH window
            }
            case REVERSE_AND_CLOSE -> {
                log.debug("Triggering reversal and account closure flag: paymentId={}", result.originalPaymentId());
                // In production: reverse, flag beneficiary account for review/closure
            }
            case REVERSE_AND_INVESTIGATE -> {
                log.debug("Triggering reversal and fraud investigation: paymentId={}", result.originalPaymentId());
                // In production: reverse, create fraud case, notify compliance
            }
            case NOTIFY_ONLY -> {
                log.debug("Sending notification only (no reversal): paymentId={}", result.originalPaymentId());
                // In production: notify originator, no financial action
            }
        }
    }
}
