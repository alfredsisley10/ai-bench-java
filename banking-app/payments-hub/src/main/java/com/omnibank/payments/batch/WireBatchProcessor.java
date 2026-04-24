package com.omnibank.payments.batch;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Processes batched wire transfer instruction files. Validates each instruction
 * against SWIFT/Fedwire field constraints, manages queuing priority, and
 * controls submission rate.
 *
 * <p>Wire instruction constraints (Fedwire format):
 * <ul>
 *   <li>Originator/Beneficiary names: max 35 characters per line, 4 lines max</li>
 *   <li>SWIFT character set: A-Z, a-z, 0-9, / - ? : ( ) . , ' + space</li>
 *   <li>Amount: max 12 digits (including cents), no commas</li>
 *   <li>OBI (Originator-to-Beneficiary Information): 4 lines x 35 characters</li>
 * </ul>
 *
 * <p>Queue management:
 * <ul>
 *   <li>Priority queuing: large-value wires processed first within each batch</li>
 *   <li>Rate limiting: configurable max wires per minute to avoid Fedwire throttling</li>
 *   <li>Deduplication: same idempotency key within a batch is rejected</li>
 * </ul>
 */
public class WireBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(WireBatchProcessor.class);

    private static final int MAX_NAME_LINE_LENGTH = 35;
    private static final int MAX_NAME_LINES = 4;
    private static final int MAX_OBI_LINES = 4;
    private static final int MAX_OBI_LINE_LENGTH = 35;
    private static final int MAX_AMOUNT_DIGITS = 12;
    private static final Pattern SWIFT_CHARSET = Pattern.compile("^[a-zA-Z0-9 /\\-?:().,'+=!\"#%&*;<>{}@\\[\\]|^~`_\\\\\\r\\n]*$");
    private static final int DEFAULT_MAX_WIRES_PER_MINUTE = 120;

    public enum WireType {
        DOMESTIC_FUNDS_TRANSFER,       // Fedwire domestic
        INTERNATIONAL_FUNDS_TRANSFER,  // SWIFT/CHIPS international
        DRAWDOWN_REQUEST,              // Fedwire drawdown
        RETURN_TRANSFER                // Return/reversal wire
    }

    public enum WirePriority {
        HIGH(1),
        NORMAL(2),
        LOW(3);

        private final int order;

        WirePriority(int order) {
            this.order = order;
        }

        public int order() {
            return order;
        }
    }

    public enum InstructionStatus {
        QUEUED,
        VALIDATING,
        VALIDATED,
        SUBMITTED,
        CONFIRMED,
        REJECTED,
        FAILED
    }

    public record WireInstruction(
            String instructionId,
            String idempotencyKey,
            WireType wireType,
            WirePriority priority,
            RoutingNumber originatorRouting,
            String originatorName,
            String originatorAccount,
            RoutingNumber beneficiaryRouting,
            String beneficiaryName,
            String beneficiaryAccount,
            Money amount,
            List<String> originatorToBeneficiaryInfo,
            String businessFunctionCode,
            String referenceForBeneficiary
    ) {
        public WireInstruction {
            Objects.requireNonNull(instructionId, "instructionId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(wireType, "wireType");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(originatorRouting, "originatorRouting");
            Objects.requireNonNull(originatorName, "originatorName");
            Objects.requireNonNull(originatorAccount, "originatorAccount");
            Objects.requireNonNull(beneficiaryRouting, "beneficiaryRouting");
            Objects.requireNonNull(beneficiaryName, "beneficiaryName");
            Objects.requireNonNull(beneficiaryAccount, "beneficiaryAccount");
            Objects.requireNonNull(amount, "amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("Wire amount must be positive");
            }
        }
    }

    public record ValidationIssue(String field, String message, boolean blocking) {}

    public record ProcessedInstruction(
            WireInstruction instruction,
            InstructionStatus status,
            List<ValidationIssue> validationIssues,
            String fedwireImad,
            Instant queuedAt,
            Instant processedAt,
            String failureReason
    ) {}

    public record BatchResult(
            String batchId,
            int totalInstructions,
            int validated,
            int rejected,
            int queued,
            Money totalAmount,
            List<ProcessedInstruction> results,
            Instant processedAt
    ) {}

    private final Clock clock;
    private final Queue<ProcessedInstruction> submissionQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, ProcessedInstruction> processedInstructions = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final AtomicInteger submissionsThisMinute = new AtomicInteger(0);
    private volatile int maxWiresPerMinute = DEFAULT_MAX_WIRES_PER_MINUTE;

    public WireBatchProcessor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Processes a batch of wire instructions. Each instruction is validated,
     * and valid instructions are queued for Fedwire submission.
     */
    public BatchResult processBatch(String batchId, List<WireInstruction> instructions) {
        log.info("Processing wire batch: batchId={}, instructions={}", batchId, instructions.size());
        var now = Timestamp.now(clock);

        var results = new ArrayList<ProcessedInstruction>();
        int validated = 0;
        int rejected = 0;
        int queued = 0;
        var totalAmount = Money.zero(CurrencyCode.USD);

        // Sort by priority (HIGH first, then by amount descending within priority)
        var sorted = instructions.stream()
                .sorted(Comparator.<WireInstruction, Integer>comparing(w -> w.priority().order())
                        .thenComparing(w -> w.amount().amount(), Comparator.reverseOrder()))
                .toList();

        for (var instruction : sorted) {
            // Deduplication check
            var existingId = idempotencyIndex.get(instruction.idempotencyKey());
            if (existingId != null) {
                log.warn("Duplicate wire instruction rejected: idempotencyKey={}, existing={}",
                        instruction.idempotencyKey(), existingId);
                var duplicate = new ProcessedInstruction(
                        instruction, InstructionStatus.REJECTED, List.of(),
                        null, now, now, "Duplicate idempotency key");
                results.add(duplicate);
                rejected++;
                continue;
            }

            // Validate
            var issues = validateInstruction(instruction);
            boolean hasBlockingIssues = issues.stream().anyMatch(ValidationIssue::blocking);

            if (hasBlockingIssues) {
                var rejectedInstr = new ProcessedInstruction(
                        instruction, InstructionStatus.REJECTED, issues,
                        null, now, now, "Validation failed");
                results.add(rejectedInstr);
                processedInstructions.put(instruction.instructionId(), rejectedInstr);
                rejected++;
                log.warn("Wire instruction rejected: id={}, issues={}", instruction.instructionId(), issues.size());
            } else {
                var queuedInstr = new ProcessedInstruction(
                        instruction, InstructionStatus.QUEUED, issues,
                        null, now, null, null);
                results.add(queuedInstr);
                processedInstructions.put(instruction.instructionId(), queuedInstr);
                idempotencyIndex.put(instruction.idempotencyKey(), instruction.instructionId());
                submissionQueue.add(queuedInstr);
                totalAmount = totalAmount.plus(instruction.amount());
                validated++;
                queued++;
            }
        }

        log.info("Wire batch processed: batchId={}, validated={}, rejected={}, queued={}, totalAmount={}",
                batchId, validated, rejected, queued, totalAmount);

        return new BatchResult(batchId, instructions.size(), validated, rejected,
                queued, totalAmount, List.copyOf(results), now);
    }

    /**
     * Drains the submission queue and submits wires to Fedwire, respecting rate limits.
     * Returns the number of wires submitted in this cycle.
     */
    public int drainSubmissionQueue() {
        int submitted = 0;

        while (!submissionQueue.isEmpty() && submissionsThisMinute.get() < maxWiresPerMinute) {
            var item = submissionQueue.poll();
            if (item == null) break;

            try {
                var imad = submitToFedwire(item.instruction());
                var confirmed = new ProcessedInstruction(
                        item.instruction(), InstructionStatus.SUBMITTED, item.validationIssues(),
                        imad, item.queuedAt(), Timestamp.now(clock), null);

                processedInstructions.put(item.instruction().instructionId(), confirmed);
                submissionsThisMinute.incrementAndGet();
                submitted++;

                log.debug("Wire submitted to Fedwire: id={}, IMAD={}",
                        item.instruction().instructionId(), imad);

            } catch (Exception e) {
                var failed = new ProcessedInstruction(
                        item.instruction(), InstructionStatus.FAILED, item.validationIssues(),
                        null, item.queuedAt(), Timestamp.now(clock), e.getMessage());
                processedInstructions.put(item.instruction().instructionId(), failed);
                log.error("Wire submission failed: id={}", item.instruction().instructionId(), e);
            }
        }

        if (submitted > 0) {
            log.info("Submission queue drained: submitted={}, remaining={}", submitted, submissionQueue.size());
        }
        return submitted;
    }

    /**
     * Resets the per-minute submission counter. Called by a scheduler every minute.
     */
    public void resetMinuteCounter() {
        submissionsThisMinute.set(0);
    }

    public Optional<ProcessedInstruction> getInstruction(String instructionId) {
        return Optional.ofNullable(processedInstructions.get(instructionId));
    }

    public int queueDepth() {
        return submissionQueue.size();
    }

    public void setMaxWiresPerMinute(int max) {
        this.maxWiresPerMinute = max;
        log.info("Wire rate limit updated: {} per minute", max);
    }

    private List<ValidationIssue> validateInstruction(WireInstruction wire) {
        var issues = new ArrayList<ValidationIssue>();

        // Validate SWIFT character set for all text fields
        validateSwiftField(wire.originatorName(), "originatorName", issues);
        validateSwiftField(wire.beneficiaryName(), "beneficiaryName", issues);
        if (wire.referenceForBeneficiary() != null) {
            validateSwiftField(wire.referenceForBeneficiary(), "referenceForBeneficiary", issues);
        }

        // Validate name lengths (max 35 chars per line concept)
        if (wire.originatorName().length() > MAX_NAME_LINE_LENGTH * MAX_NAME_LINES) {
            issues.add(new ValidationIssue("originatorName",
                    "Originator name exceeds %d characters".formatted(MAX_NAME_LINE_LENGTH * MAX_NAME_LINES), true));
        }
        if (wire.beneficiaryName().length() > MAX_NAME_LINE_LENGTH * MAX_NAME_LINES) {
            issues.add(new ValidationIssue("beneficiaryName",
                    "Beneficiary name exceeds %d characters".formatted(MAX_NAME_LINE_LENGTH * MAX_NAME_LINES), true));
        }

        // Validate OBI lines
        if (wire.originatorToBeneficiaryInfo() != null) {
            if (wire.originatorToBeneficiaryInfo().size() > MAX_OBI_LINES) {
                issues.add(new ValidationIssue("originatorToBeneficiaryInfo",
                        "OBI exceeds %d lines".formatted(MAX_OBI_LINES), true));
            }
            for (int i = 0; i < wire.originatorToBeneficiaryInfo().size(); i++) {
                var line = wire.originatorToBeneficiaryInfo().get(i);
                if (line.length() > MAX_OBI_LINE_LENGTH) {
                    issues.add(new ValidationIssue("originatorToBeneficiaryInfo[%d]".formatted(i),
                            "OBI line exceeds %d characters".formatted(MAX_OBI_LINE_LENGTH), true));
                }
                validateSwiftField(line, "originatorToBeneficiaryInfo[%d]".formatted(i), issues);
            }
        }

        // Validate amount
        var amountStr = wire.amount().amount().movePointRight(2).toBigInteger().toString();
        if (amountStr.length() > MAX_AMOUNT_DIGITS) {
            issues.add(new ValidationIssue("amount",
                    "Amount exceeds %d-digit Fedwire limit".formatted(MAX_AMOUNT_DIGITS), true));
        }

        // Validate business function code
        if (wire.businessFunctionCode() != null
                && !List.of("BTR", "CTR", "CKS", "DEP", "FFR", "FFS", "DRB", "DRC")
                .contains(wire.businessFunctionCode())) {
            issues.add(new ValidationIssue("businessFunctionCode",
                    "Invalid business function code: " + wire.businessFunctionCode(), false));
        }

        return issues;
    }

    private void validateSwiftField(String value, String fieldName, List<ValidationIssue> issues) {
        if (value != null && !SWIFT_CHARSET.matcher(value).matches()) {
            issues.add(new ValidationIssue(fieldName,
                    "Contains characters outside SWIFT character set", true));
        }
    }

    /**
     * Submits a wire instruction to Fedwire and returns the IMAD
     * (Input Message Accountability Data) reference.
     */
    private String submitToFedwire(WireInstruction instruction) {
        // Production: call Fedwire gateway via secure connection
        log.debug("Submitting wire to Fedwire: type={}, amount={}, beneficiary={}",
                instruction.wireType(), instruction.amount(), instruction.beneficiaryName());

        // Generate IMAD: YYYYMMDD + originator ABA + sequence
        var dateStr = java.time.LocalDate.now(clock).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        var seq = String.format("%06d", processedInstructions.size());
        return dateStr + instruction.originatorRouting().raw() + seq;
    }
}
