package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Processes inbound clearing files from card networks and books settlement
 * into the general ledger.
 *
 * <p>Network-specific clearing formats:
 * <ul>
 *   <li><b>Visa Base II:</b> daily clearing with separate presentment and
 *       chargeback cycles; interchange netted at the file level.</li>
 *   <li><b>Mastercard ClearingIPM:</b> similar cadence; uses IRDs
 *       (interchange rate designators) carried per record.</li>
 *   <li><b>Amex CAPN:</b> closed-loop network; no interchange netting —
 *       Amex pays the merchant and collects from the issuer via direct
 *       settlement.</li>
 *   <li><b>Discover DCI:</b> similar to Visa; treated as Visa by this
 *       simplified engine.</li>
 * </ul>
 *
 * <p>Each record produces at most three journal entries: the transaction
 * itself (debit cardholder receivable, credit network settlement), the
 * interchange fee (expense), and the network fee (expense). Reversals
 * flip sign; purchase returns go against the original receivable.
 */
@Service
public class CardSettlementProcessor {

    private static final Logger log = LoggerFactory.getLogger(CardSettlementProcessor.class);

    // GL code set — illustrative; production banks have a much deeper chart.
    private static final GlAccountCode CARDHOLDER_RECEIVABLE =
            new GlAccountCode("ASS-1400-001");
    private static final GlAccountCode CARD_INTERCHANGE_EXPENSE =
            new GlAccountCode("EXP-5100-001");
    private static final GlAccountCode CARD_NETWORK_FEE_EXPENSE =
            new GlAccountCode("EXP-5100-002");
    private static final GlAccountCode CARD_INTERCHANGE_REVENUE =
            new GlAccountCode("REV-4200-001");

    /** Network settlement GL codes — each network has its own. */
    private static final Map<CardNetwork, GlAccountCode> NETWORK_SETTLEMENT_GL;

    static {
        NETWORK_SETTLEMENT_GL = new EnumMap<>(CardNetwork.class);
        NETWORK_SETTLEMENT_GL.put(CardNetwork.VISA, new GlAccountCode("LIA-2500-001"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.MASTERCARD, new GlAccountCode("LIA-2500-002"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.AMEX, new GlAccountCode("LIA-2500-003"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.DISCOVER, new GlAccountCode("LIA-2500-004"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.PULSE, new GlAccountCode("LIA-2500-005"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.STAR, new GlAccountCode("LIA-2500-006"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.INTERLINK, new GlAccountCode("LIA-2500-007"));
        NETWORK_SETTLEMENT_GL.put(CardNetwork.MAESTRO, new GlAccountCode("LIA-2500-008"));
    }

    /**
     * Domain event emitted for every settled clearing record — used by
     * reconciliation and customer notification pipelines.
     */
    public record SettlementEvent(
            UUID eventId,
            Instant occurredAt,
            UUID authorizationId,
            UUID cardId,
            CardNetwork network,
            Money transactionAmount,
            Money interchangeFee,
            Money networkFee,
            boolean reversal,
            boolean purchaseReturn) implements DomainEvent {

        @Override
        public String eventType() {
            return "cards.settled";
        }
    }

    /**
     * Summary returned after a clearing file is processed. Used by
     * reconciliation dashboards.
     */
    public record SettlementSummary(
            String fileId,
            CardNetwork network,
            LocalDate cycleDate,
            int recordCount,
            int successCount,
            int failedCount,
            Money grossTransactions,
            Money totalInterchange,
            Money totalNetworkFees
    ) {}

    /** Tracks processed file ids to make ingestion idempotent. */
    private final java.util.Set<String> processedFileIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final PostingService posting;
    private final EventBus events;
    private final Clock clock;

    public CardSettlementProcessor(PostingService posting, EventBus events, Clock clock) {
        this.posting = Objects.requireNonNull(posting, "posting");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Ingest and post a clearing file end-to-end. Idempotent on
     * {@code file.fileId()} — re-submitting the same file is a no-op.
     */
    @Transactional
    public SettlementSummary process(ClearingFile file) {
        Objects.requireNonNull(file, "file");
        if (!processedFileIds.add(file.fileId())) {
            log.warn("Clearing file already processed, skipping: fileId={}", file.fileId());
            return emptySummary(file);
        }

        log.info("Processing clearing file: fileId={}, network={}, records={}, cycleDate={}",
                file.fileId(), file.network(), file.records().size(), file.cycleDate());

        int success = 0;
        int failed = 0;
        Money gross = null;
        Money interchangeTotal = null;
        Money networkTotal = null;

        for (var record : file.records()) {
            try {
                postRecord(file.network(), file.cycleDate(), record);
                success++;
                gross = accumulate(gross, record.transactionAmount());
                interchangeTotal = accumulate(interchangeTotal, record.interchangeFee());
                networkTotal = accumulate(networkTotal, record.networkFee());
            } catch (Exception e) {
                failed++;
                log.error("Settlement failed for auth={}: {}",
                        record.authorizationId(), e.getMessage(), e);
            }
        }

        var currency = file.records().isEmpty()
                ? CurrencyCode.USD
                : file.records().get(0).transactionAmount().currency();

        var summary = new SettlementSummary(
                file.fileId(), file.network(), file.cycleDate(),
                file.records().size(), success, failed,
                gross == null ? Money.zero(currency) : gross,
                interchangeTotal == null ? Money.zero(currency) : interchangeTotal,
                networkTotal == null ? Money.zero(currency) : networkTotal);

        log.info("Clearing file processed: fileId={}, success={}, failed={}, gross={}",
                file.fileId(), success, failed, summary.grossTransactions());
        return summary;
    }

    /**
     * Apply a single clearing record as a set of balanced journal entries.
     */
    void postRecord(CardNetwork network,
                    LocalDate cycleDate,
                    ClearingFile.ClearingRecord record) {
        var settlementGl = NETWORK_SETTLEMENT_GL.get(network);
        if (settlementGl == null) {
            throw new IllegalStateException("No settlement GL for network: " + network);
        }

        if (record.reversal()) {
            postReversal(network, cycleDate, record, settlementGl);
        } else if (record.purchaseReturn()) {
            postPurchaseReturn(network, cycleDate, record, settlementGl);
        } else {
            postPurchase(network, cycleDate, record, settlementGl);
        }

        postInterchangeFee(network, cycleDate, record);
        postNetworkFee(network, cycleDate, record, settlementGl);
        publishSettlementEvent(network, record);
    }

    /** Post the main presentment (purchase) leg. */
    private void postPurchase(CardNetwork network,
                              LocalDate cycleDate,
                              ClearingFile.ClearingRecord record,
                              GlAccountCode settlementGl) {
        if (record.transactionAmount().isZero()) return;

        var je = new JournalEntry(
                UUID.randomUUID(), cycleDate,
                "CARD-PURCHASE-" + record.authorizationId(),
                "Card purchase settlement auth=" + record.authorizationId(),
                List.of(
                        PostingLine.debit(CARDHOLDER_RECEIVABLE, record.transactionAmount(),
                                "Purchase receivable"),
                        PostingLine.credit(settlementGl, record.transactionAmount(),
                                "Due to " + network.name())
                ));
        posting.post(je);
    }

    /** Post a reversal — symmetric with purchase but with flipped sides. */
    private void postReversal(CardNetwork network,
                              LocalDate cycleDate,
                              ClearingFile.ClearingRecord record,
                              GlAccountCode settlementGl) {
        if (record.transactionAmount().isZero()) return;

        var je = new JournalEntry(
                UUID.randomUUID(), cycleDate,
                "CARD-REVERSAL-" + record.authorizationId(),
                "Card reversal auth=" + record.authorizationId(),
                List.of(
                        PostingLine.debit(settlementGl, record.transactionAmount(),
                                "Reversal back to " + network.name()),
                        PostingLine.credit(CARDHOLDER_RECEIVABLE, record.transactionAmount(),
                                "Reversal clears receivable")
                ));
        posting.post(je);
    }

    /** Post a purchase return (refund presentment). */
    private void postPurchaseReturn(CardNetwork network,
                                    LocalDate cycleDate,
                                    ClearingFile.ClearingRecord record,
                                    GlAccountCode settlementGl) {
        if (record.transactionAmount().isZero()) return;

        var je = new JournalEntry(
                UUID.randomUUID(), cycleDate,
                "CARD-RETURN-" + record.authorizationId(),
                "Card purchase return auth=" + record.authorizationId(),
                List.of(
                        PostingLine.debit(settlementGl, record.transactionAmount(),
                                "Refund due from " + network.name()),
                        PostingLine.credit(CARDHOLDER_RECEIVABLE, record.transactionAmount(),
                                "Refund reduces receivable")
                ));
        posting.post(je);
    }

    /** Book the interchange cost — an expense for debit cards, revenue offset for credit. */
    private void postInterchangeFee(CardNetwork network,
                                    LocalDate cycleDate,
                                    ClearingFile.ClearingRecord record) {
        if (record.interchangeFee().isZero()) return;

        // For AMEX there is no interchange flow — the network pays the
        // merchant directly, so skip.
        if (network == CardNetwork.AMEX) return;

        // Debit rail: issuer pays the interchange expense.
        // Credit rail: issuer earns interchange revenue from the network.
        GlAccountCode feeGl = network.isDebitRail()
                ? CARD_INTERCHANGE_EXPENSE
                : CARD_INTERCHANGE_REVENUE;
        var settlementGl = NETWORK_SETTLEMENT_GL.get(network);

        var lines = network.isDebitRail()
                ? List.of(
                        PostingLine.debit(feeGl, record.interchangeFee(), "Interchange expense"),
                        PostingLine.credit(settlementGl, record.interchangeFee(),
                                "Interchange due to " + network.name()))
                : List.of(
                        PostingLine.debit(settlementGl, record.interchangeFee(),
                                "Interchange receivable"),
                        PostingLine.credit(feeGl, record.interchangeFee(),
                                "Interchange revenue"));

        var je = new JournalEntry(
                UUID.randomUUID(), cycleDate,
                "CARD-ICHG-" + record.authorizationId(),
                "Interchange allocation auth=" + record.authorizationId(),
                lines);
        posting.post(je);
    }

    /** Book the per-network assessment fee. */
    private void postNetworkFee(CardNetwork network,
                                LocalDate cycleDate,
                                ClearingFile.ClearingRecord record,
                                GlAccountCode settlementGl) {
        if (record.networkFee().isZero()) return;

        var je = new JournalEntry(
                UUID.randomUUID(), cycleDate,
                "CARD-NETFEE-" + record.authorizationId(),
                "Network assessment fee auth=" + record.authorizationId(),
                List.of(
                        PostingLine.debit(CARD_NETWORK_FEE_EXPENSE, record.networkFee(),
                                "Network fee expense"),
                        PostingLine.credit(settlementGl, record.networkFee(),
                                "Network fee due to " + network.name())
                ));
        posting.post(je);
    }

    private void publishSettlementEvent(CardNetwork network,
                                         ClearingFile.ClearingRecord record) {
        try {
            events.publish(new SettlementEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    record.authorizationId(), record.cardId(), network,
                    record.transactionAmount(), record.interchangeFee(),
                    record.networkFee(), record.reversal(), record.purchaseReturn()));
        } catch (Exception e) {
            log.warn("Failed to publish settlement event for auth={}: {}",
                    record.authorizationId(), e.getMessage());
        }
    }

    private Money accumulate(Money current, Money add) {
        if (current == null) return add;
        return current.plus(add);
    }

    private SettlementSummary emptySummary(ClearingFile file) {
        return new SettlementSummary(
                file.fileId(), file.network(), file.cycleDate(),
                0, 0, 0,
                Money.zero(CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                Money.zero(CurrencyCode.USD));
    }

    /** Check whether a given file id has been ingested already. */
    public boolean isProcessed(String fileId) {
        return processedFileIds.contains(fileId);
    }

    /**
     * Roll up a list of clearing files into one summary. Handy for a
     * cycle-close report combining all networks for a given day.
     */
    public SettlementSummary processAll(List<ClearingFile> files) {
        List<SettlementSummary> summaries = new ArrayList<>();
        for (var f : files) {
            summaries.add(process(f));
        }
        return combine(summaries);
    }

    static SettlementSummary combine(List<SettlementSummary> summaries) {
        if (summaries.isEmpty()) {
            return new SettlementSummary("aggregate", CardNetwork.VISA,
                    LocalDate.now(), 0, 0, 0,
                    Money.zero(CurrencyCode.USD),
                    Money.zero(CurrencyCode.USD),
                    Money.zero(CurrencyCode.USD));
        }
        int total = 0, ok = 0, bad = 0;
        Money gross = summaries.get(0).grossTransactions();
        Money ich = summaries.get(0).totalInterchange();
        Money net = summaries.get(0).totalNetworkFees();
        for (int i = 1; i < summaries.size(); i++) {
            gross = gross.plus(summaries.get(i).grossTransactions());
            ich = ich.plus(summaries.get(i).totalInterchange());
            net = net.plus(summaries.get(i).totalNetworkFees());
        }
        for (var s : summaries) {
            total += s.recordCount();
            ok += s.successCount();
            bad += s.failedCount();
        }
        return new SettlementSummary("aggregate", summaries.get(0).network(),
                summaries.get(0).cycleDate(), total, ok, bad, gross, ich, net);
    }
}
