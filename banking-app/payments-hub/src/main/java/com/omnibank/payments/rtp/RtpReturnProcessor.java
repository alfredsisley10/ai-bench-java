package com.omnibank.payments.rtp;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes RTP return and recall (Request for Return of Funds) messages.
 *
 * <p>RTP payments are irrevocable at the network level; however, TCH provides a
 * "Request for Return of Funds" (RFR) mechanism where the originating bank can
 * ask the beneficiary bank to return funds. Key constraints:
 * <ul>
 *   <li>RFR must be sent within the network-defined time window (currently TCH allows
 *       RFR up to calendar day + 1, but bank policy may be narrower)</li>
 *   <li>The beneficiary bank has discretion to honor or decline the return</li>
 *   <li>Bilateral agreements between banks may extend or restrict return windows</li>
 *   <li>Return reason codes follow ISO 20022 ExternalReturnReason1Code</li>
 * </ul>
 *
 * <p>This processor handles both inbound returns (we are the beneficiary bank) and
 * outbound return requests (we are the originating bank requesting funds back).
 */
public class RtpReturnProcessor {

    private static final Logger log = LoggerFactory.getLogger(RtpReturnProcessor.class);

    private static final Duration DEFAULT_RFR_WINDOW = Duration.ofHours(48);
    private static final Duration RESPONSE_DEADLINE = Duration.ofHours(24);

    /**
     * ISO 20022 return reason codes relevant to RTP.
     */
    public enum ReturnReasonCode {
        AC03("Account invalid"),
        AC04("Closed account"),
        AC06("Blocked account"),
        AG01("Transaction forbidden"),
        AM02("Not allowed amount"),
        AM04("Insufficient funds"),
        AM09("Wrong amount"),
        BE04("Missing creditor address"),
        CUST("Requested by customer"),
        DS02("Order cancelled"),
        DUPL("Duplicate payment"),
        FF01("Invalid file format"),
        FOCR("Following cancellation request"),
        FR01("Fraud"),
        MD01("No mandate"),
        MS02("Not specified reason by customer"),
        NARR("Narrative / free text reason"),
        RC01("Bank identifier incorrect"),
        SL02("Specific service offered by debtor agent"),
        TM01("Cut off time"),
        TECH("Technical problem");

        private final String description;

        ReturnReasonCode(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public enum ReturnRequestStatus {
        INITIATED,
        SENT_TO_NETWORK,
        PENDING_BENEFICIARY_RESPONSE,
        ACCEPTED,
        DECLINED,
        PARTIALLY_RETURNED,
        EXPIRED,
        FAILED
    }

    public record ReturnRequest(
            String returnRequestId,
            PaymentId originalPaymentId,
            String originalSettlementRef,
            ReturnReasonCode reasonCode,
            String additionalInfo,
            Money originalAmount,
            Money requestedReturnAmount,
            RoutingNumber beneficiaryBankRouting,
            Instant requestedAt
    ) {
        public ReturnRequest {
            Objects.requireNonNull(returnRequestId, "returnRequestId");
            Objects.requireNonNull(originalPaymentId, "originalPaymentId");
            Objects.requireNonNull(originalSettlementRef, "originalSettlementRef");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(originalAmount, "originalAmount");
            Objects.requireNonNull(requestedReturnAmount, "requestedReturnAmount");
            Objects.requireNonNull(beneficiaryBankRouting, "beneficiaryBankRouting");
            Objects.requireNonNull(requestedAt, "requestedAt");
            if (requestedReturnAmount.amount().compareTo(originalAmount.amount()) > 0) {
                throw new IllegalArgumentException("Return amount cannot exceed original amount");
            }
        }
    }

    public record ReturnResponse(
            String returnRequestId,
            ReturnRequestStatus status,
            Money returnedAmount,
            Instant respondedAt,
            String declineReason
    ) {}

    public record BilateralAgreement(
            RoutingNumber counterpartyRouting,
            String counterpartyName,
            Duration extendedReturnWindow,
            boolean autoAcceptReturns,
            Money autoAcceptThreshold
    ) {
        public BilateralAgreement {
            Objects.requireNonNull(counterpartyRouting, "counterpartyRouting");
            Objects.requireNonNull(counterpartyName, "counterpartyName");
        }
    }

    private final Map<String, ReturnRequest> activeReturnRequests = new ConcurrentHashMap<>();
    private final Map<String, ReturnResponse> completedReturns = new ConcurrentHashMap<>();
    private final Map<String, BilateralAgreement> bilateralAgreements = new ConcurrentHashMap<>();
    private final Clock clock;

    public RtpReturnProcessor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Initiates an outbound Request for Return of Funds.
     * Validates the return window and constructs the RFR message.
     */
    public ReturnRequest initiateReturnRequest(
            PaymentId originalPaymentId,
            String originalSettlementRef,
            Instant originalSettlementTime,
            ReturnReasonCode reasonCode,
            String additionalInfo,
            Money originalAmount,
            Money requestedReturnAmount,
            RoutingNumber beneficiaryBankRouting) {

        var now = Timestamp.now(clock);

        // Determine applicable return window (check bilateral agreements first)
        var effectiveWindow = resolveReturnWindow(beneficiaryBankRouting);
        var elapsed = Duration.between(originalSettlementTime, now);

        if (elapsed.compareTo(effectiveWindow) > 0) {
            throw new ReturnWindowExpiredException(
                    "Return request window expired. Settlement was %s ago, window is %s. Original ref: %s"
                            .formatted(formatDuration(elapsed), formatDuration(effectiveWindow),
                                    originalSettlementRef));
        }

        // Fraud returns bypass time window restrictions
        if (reasonCode == ReturnReasonCode.FR01) {
            log.info("Fraud return request — time window check bypassed: originalRef={}",
                    originalSettlementRef);
        }

        var requestId = "RFR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var request = new ReturnRequest(
                requestId, originalPaymentId, originalSettlementRef, reasonCode,
                additionalInfo, originalAmount, requestedReturnAmount,
                beneficiaryBankRouting, now);

        activeReturnRequests.put(requestId, request);

        log.info("RTP return request initiated: id={}, originalRef={}, reason={}, amount={}",
                requestId, originalSettlementRef, reasonCode, requestedReturnAmount);

        sendReturnRequestToNetwork(request);
        return request;
    }

    /**
     * Processes an inbound return request received from the network.
     * Applies bilateral agreement rules and auto-accept thresholds.
     */
    public ReturnResponse processInboundReturnRequest(ReturnRequest inboundRequest) {
        var now = Timestamp.now(clock);
        var routing = inboundRequest.beneficiaryBankRouting();

        log.info("Processing inbound RTP return request: id={}, originalRef={}, reason={}, amount={}",
                inboundRequest.returnRequestId(), inboundRequest.originalSettlementRef(),
                inboundRequest.reasonCode(), inboundRequest.requestedReturnAmount());

        // Check bilateral agreements for auto-accept
        var agreement = bilateralAgreements.get(routing.raw());
        if (agreement != null && agreement.autoAcceptReturns()) {
            if (agreement.autoAcceptThreshold() == null
                    || inboundRequest.requestedReturnAmount().compareTo(agreement.autoAcceptThreshold()) <= 0) {
                log.info("Auto-accepting return per bilateral agreement with {}: amount={}",
                        agreement.counterpartyName(), inboundRequest.requestedReturnAmount());

                var response = new ReturnResponse(
                        inboundRequest.returnRequestId(), ReturnRequestStatus.ACCEPTED,
                        inboundRequest.requestedReturnAmount(), now, null);
                completedReturns.put(inboundRequest.returnRequestId(), response);
                return response;
            }
        }

        // Fraud reason codes are auto-accepted per bank policy
        if (inboundRequest.reasonCode() == ReturnReasonCode.FR01) {
            log.info("Auto-accepting fraud return request: id={}", inboundRequest.returnRequestId());
            var response = new ReturnResponse(
                    inboundRequest.returnRequestId(), ReturnRequestStatus.ACCEPTED,
                    inboundRequest.requestedReturnAmount(), now, null);
            completedReturns.put(inboundRequest.returnRequestId(), response);
            return response;
        }

        // Queue for manual review
        activeReturnRequests.put(inboundRequest.returnRequestId(), inboundRequest);
        log.info("Return request queued for manual review: id={}", inboundRequest.returnRequestId());

        return new ReturnResponse(
                inboundRequest.returnRequestId(), ReturnRequestStatus.PENDING_BENEFICIARY_RESPONSE,
                null, now, null);
    }

    /**
     * Manually responds to a pending return request (accept or decline).
     */
    public ReturnResponse respondToReturnRequest(
            String returnRequestId, boolean accept, Money returnAmount, String declineReason) {
        var request = activeReturnRequests.get(returnRequestId);
        if (request == null) {
            throw new IllegalArgumentException("No active return request found: " + returnRequestId);
        }

        var now = Timestamp.now(clock);

        // Check response deadline
        if (Duration.between(request.requestedAt(), now).compareTo(RESPONSE_DEADLINE) > 0) {
            log.warn("Response deadline exceeded for return request: id={}", returnRequestId);
        }

        ReturnResponse response;
        if (accept) {
            var effectiveReturnAmount = returnAmount != null ? returnAmount : request.requestedReturnAmount();
            var status = effectiveReturnAmount.compareTo(request.requestedReturnAmount()) < 0
                    ? ReturnRequestStatus.PARTIALLY_RETURNED
                    : ReturnRequestStatus.ACCEPTED;

            response = new ReturnResponse(returnRequestId, status, effectiveReturnAmount, now, null);
            log.info("Return request accepted: id={}, amount={}", returnRequestId, effectiveReturnAmount);
        } else {
            response = new ReturnResponse(
                    returnRequestId, ReturnRequestStatus.DECLINED, null, now,
                    declineReason != null ? declineReason : "Return request declined by beneficiary bank");
            log.info("Return request declined: id={}, reason={}", returnRequestId, response.declineReason());
        }

        completedReturns.put(returnRequestId, response);
        activeReturnRequests.remove(returnRequestId);
        sendReturnResponseToNetwork(request, response);
        return response;
    }

    /**
     * Registers a bilateral agreement with a counterparty bank.
     */
    public void registerBilateralAgreement(BilateralAgreement agreement) {
        bilateralAgreements.put(agreement.counterpartyRouting().raw(), agreement);
        log.info("Bilateral agreement registered: counterparty={}, autoAccept={}, window={}",
                agreement.counterpartyName(), agreement.autoAcceptReturns(),
                agreement.extendedReturnWindow());
    }

    /**
     * Sweeps expired return requests that have exceeded the response deadline.
     */
    public int sweepExpiredRequests() {
        var now = Timestamp.now(clock);
        int expiredCount = 0;

        for (var entry : activeReturnRequests.entrySet()) {
            var request = entry.getValue();
            if (Duration.between(request.requestedAt(), now).compareTo(RESPONSE_DEADLINE) > 0) {
                var response = new ReturnResponse(
                        request.returnRequestId(), ReturnRequestStatus.EXPIRED,
                        null, now, "Response deadline exceeded");

                completedReturns.put(request.returnRequestId(), response);
                activeReturnRequests.remove(entry.getKey());

                log.warn("Return request expired without response: id={}", request.returnRequestId());
                expiredCount++;
            }
        }
        return expiredCount;
    }

    public Optional<ReturnResponse> getReturnStatus(String returnRequestId) {
        return Optional.ofNullable(completedReturns.get(returnRequestId));
    }

    public List<ReturnRequest> getPendingReturnRequests() {
        return List.copyOf(activeReturnRequests.values());
    }

    private Duration resolveReturnWindow(RoutingNumber beneficiaryRouting) {
        var agreement = bilateralAgreements.get(beneficiaryRouting.raw());
        if (agreement != null && agreement.extendedReturnWindow() != null) {
            return agreement.extendedReturnWindow();
        }
        return DEFAULT_RFR_WINDOW;
    }

    private void sendReturnRequestToNetwork(ReturnRequest request) {
        log.debug("Sending RFR to TCH RTP network: id={}, originalRef={}",
                request.returnRequestId(), request.originalSettlementRef());
    }

    private void sendReturnResponseToNetwork(ReturnRequest request, ReturnResponse response) {
        log.debug("Sending return response to TCH RTP network: id={}, status={}",
                request.returnRequestId(), response.status());
    }

    private String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        return "%dh %dm".formatted(hours, minutes);
    }

    public static final class ReturnWindowExpiredException extends RuntimeException {
        public ReturnWindowExpiredException(String message) {
            super(message);
        }
    }
}
