package com.omnibank.payments.batch;

import com.omnibank.shared.domain.RoutingNumber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds NACHA-compliant ACH batch files for origination.
 *
 * <p>NACHA file format structure:
 * <pre>
 *   File Header Record (1)
 *     Batch Header Record (1)
 *       Entry Detail Record (6)
 *       [Addenda Record (7)] — optional
 *     Batch Control Record (8)
 *   File Control Record (9)
 * </pre>
 *
 * <p>Critical NACHA constraints enforced:
 * <ul>
 *   <li>All records are exactly 94 characters (padded with spaces)</li>
 *   <li>Entry hash is sum of routing numbers in the batch (mod 10^10)</li>
 *   <li>Batch control totals must reconcile with individual entries</li>
 *   <li>File control record aggregates all batch totals</li>
 *   <li>Blocking factor: file padded to multiple of 10 records with '9' records</li>
 * </ul>
 */
public class AchBatchBuilder {

    private static final Logger log = LoggerFactory.getLogger(AchBatchBuilder.class);

    private static final int RECORD_LENGTH = 94;
    private static final int BLOCKING_FACTOR = 10;
    private static final DateTimeFormatter NACHA_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter NACHA_TIME = DateTimeFormatter.ofPattern("HHmm");

    public enum TransactionCode {
        CHECKING_CREDIT(22),
        CHECKING_DEBIT(27),
        CHECKING_PRENOTE_CREDIT(23),
        CHECKING_PRENOTE_DEBIT(28),
        SAVINGS_CREDIT(32),
        SAVINGS_DEBIT(37),
        SAVINGS_PRENOTE_CREDIT(33),
        SAVINGS_PRENOTE_DEBIT(38);

        private final int code;

        TransactionCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public boolean isCredit() {
            return code == 22 || code == 23 || code == 32 || code == 33;
        }

        public boolean isDebit() {
            return code == 27 || code == 28 || code == 37 || code == 38;
        }
    }

    public enum ServiceClassCode {
        MIXED(200),
        CREDITS_ONLY(220),
        DEBITS_ONLY(225);

        private final int code;

        ServiceClassCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public enum StandardEntryClass {
        PPD,  // Prearranged Payment and Deposit
        CCD,  // Corporate Credit or Debit
        CTX,  // Corporate Trade Exchange
        WEB,  // Internet-Initiated
        TEL,  // Telephone-Initiated
        IAT   // International ACH Transaction
    }

    public record EntryDetail(
            TransactionCode transactionCode,
            RoutingNumber receivingDfi,
            String dfiAccountNumber,
            BigDecimal amount,
            String individualId,
            String individualName,
            String discretionaryData,
            boolean hasAddenda,
            String addendaInfo
    ) {
        public EntryDetail {
            Objects.requireNonNull(transactionCode, "transactionCode");
            Objects.requireNonNull(receivingDfi, "receivingDfi");
            Objects.requireNonNull(dfiAccountNumber, "dfiAccountNumber");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(individualName, "individualName");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("Entry amount must be non-negative");
            }
            if (individualName.length() > 22) {
                throw new IllegalArgumentException("Individual name exceeds 22 characters");
            }
            if (dfiAccountNumber.length() > 17) {
                throw new IllegalArgumentException("DFI account number exceeds 17 characters");
            }
        }
    }

    public record BatchConfig(
            ServiceClassCode serviceClass,
            String companyName,
            String companyDiscretionary,
            String companyId,
            StandardEntryClass entryClass,
            String companyEntryDescription,
            LocalDate companyDescriptiveDate,
            LocalDate effectiveEntryDate,
            RoutingNumber originatingDfi
    ) {
        public BatchConfig {
            Objects.requireNonNull(serviceClass, "serviceClass");
            Objects.requireNonNull(companyName, "companyName");
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(entryClass, "entryClass");
            Objects.requireNonNull(companyEntryDescription, "companyEntryDescription");
            Objects.requireNonNull(effectiveEntryDate, "effectiveEntryDate");
            Objects.requireNonNull(originatingDfi, "originatingDfi");
            if (companyName.length() > 16) {
                throw new IllegalArgumentException("Company name exceeds 16 characters");
            }
            if (companyEntryDescription.length() > 10) {
                throw new IllegalArgumentException("Entry description exceeds 10 characters");
            }
        }
    }

    private final String immediateDestination;
    private final String immediateOrigin;
    private final String immediateDestinationName;
    private final String immediateOriginName;
    private final List<BatchConfig> batches = new ArrayList<>();
    private final List<List<EntryDetail>> batchEntries = new ArrayList<>();
    private int fileIdModifier = 'A';

    public AchBatchBuilder(String immediateDestination, String immediateOrigin,
                           String immediateDestinationName, String immediateOriginName) {
        this.immediateDestination = padRight(Objects.requireNonNull(immediateDestination), 10);
        this.immediateOrigin = padRight(Objects.requireNonNull(immediateOrigin), 10);
        this.immediateDestinationName = padRight(Objects.requireNonNull(immediateDestinationName), 23);
        this.immediateOriginName = padRight(Objects.requireNonNull(immediateOriginName), 23);
    }

    /**
     * Adds a batch with its entries to the file.
     */
    public AchBatchBuilder addBatch(BatchConfig config, List<EntryDetail> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Batch must contain at least one entry");
        }
        batches.add(config);
        batchEntries.add(new ArrayList<>(entries));
        log.debug("Batch added: company={}, entries={}, class={}",
                config.companyName(), entries.size(), config.entryClass());
        return this;
    }

    /**
     * Builds the complete NACHA ACH file as a string.
     *
     * @return the NACHA-formatted file content
     */
    public String build() {
        if (batches.isEmpty()) {
            throw new IllegalStateException("File must contain at least one batch");
        }

        var sb = new StringBuilder();
        var now = java.time.LocalDateTime.now();

        // File Header Record (Record Type 1)
        sb.append(buildFileHeader(now));

        int totalBatchCount = 0;
        int totalEntryAddendaCount = 0;
        long totalDebitAmount = 0;
        long totalCreditAmount = 0;
        long fileEntryHash = 0;

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            var config = batches.get(batchIdx);
            var entries = batchEntries.get(batchIdx);
            int batchNumber = batchIdx + 1;

            // Batch Header Record (Record Type 5)
            sb.append(buildBatchHeader(config, batchNumber));

            long batchEntryHash = 0;
            long batchDebitAmount = 0;
            long batchCreditAmount = 0;
            int entryAddendaCount = 0;
            int traceSequence = 1;

            for (var entry : entries) {
                // Entry Detail Record (Record Type 6)
                var traceNumber = config.originatingDfi().raw().substring(0, 8)
                        + padLeft(String.valueOf(traceSequence), 7, '0');
                sb.append(buildEntryDetail(entry, traceNumber));
                entryAddendaCount++;

                // Addenda Record (Record Type 7) — optional
                if (entry.hasAddenda() && entry.addendaInfo() != null) {
                    sb.append(buildAddendaRecord(entry.addendaInfo(), traceNumber));
                    entryAddendaCount++;
                }

                // Hash: first 8 digits of receiving DFI routing
                long routingHash = Long.parseLong(entry.receivingDfi().raw().substring(0, 8));
                batchEntryHash += routingHash;

                // Amount accumulators (in cents)
                long amountCents = entry.amount().movePointRight(2).longValueExact();
                if (entry.transactionCode().isCredit()) {
                    batchCreditAmount += amountCents;
                } else if (entry.transactionCode().isDebit()) {
                    batchDebitAmount += amountCents;
                }

                traceSequence++;
            }

            // Batch Control Record (Record Type 8)
            sb.append(buildBatchControl(config, batchNumber, entryAddendaCount,
                    batchEntryHash, batchDebitAmount, batchCreditAmount));

            totalBatchCount++;
            totalEntryAddendaCount += entryAddendaCount;
            totalDebitAmount += batchDebitAmount;
            totalCreditAmount += batchCreditAmount;
            fileEntryHash += batchEntryHash;
        }

        // File Control Record (Record Type 9)
        sb.append(buildFileControl(totalBatchCount, totalEntryAddendaCount,
                fileEntryHash, totalDebitAmount, totalCreditAmount));

        // Pad to blocking factor (multiple of 10 records)
        int totalRecords = countRecords(sb.toString());
        int paddingNeeded = (BLOCKING_FACTOR - (totalRecords % BLOCKING_FACTOR)) % BLOCKING_FACTOR;
        for (int i = 0; i < paddingNeeded; i++) {
            sb.append(padRight("9", RECORD_LENGTH, '9')).append('\n');
        }

        log.info("ACH file built: batches={}, entries={}, totalDebit={}, totalCredit={}",
                totalBatchCount, totalEntryAddendaCount, totalDebitAmount, totalCreditAmount);

        return sb.toString();
    }

    private String buildFileHeader(java.time.LocalDateTime now) {
        // Record Type 1: File Header
        return "1"                                          // Record Type Code
                + "01"                                      // Priority Code
                + " " + immediateDestination                // Immediate Destination (b + 10)
                + immediateOrigin                           // Immediate Origin (10)
                + now.format(NACHA_DATE)                    // File Creation Date (6)
                + now.format(NACHA_TIME)                    // File Creation Time (4)
                + (char) fileIdModifier                     // File ID Modifier (1)
                + "094"                                     // Record Size (3)
                + "10"                                      // Blocking Factor (2)
                + "1"                                       // Format Code (1)
                + immediateDestinationName                  // Immediate Destination Name (23)
                + immediateOriginName                       // Immediate Origin Name (23)
                + padRight("", 8)                           // Reference Code (8)
                + '\n';
    }

    private String buildBatchHeader(BatchConfig config, int batchNumber) {
        var descDate = config.companyDescriptiveDate() != null
                ? config.companyDescriptiveDate().format(NACHA_DATE) : "      ";
        // Record Type 5: Batch Header
        return "5"                                          // Record Type Code
                + String.valueOf(config.serviceClass().code())  // Service Class Code (3)
                + padRight(config.companyName(), 16)        // Company Name (16)
                + padRight(nvl(config.companyDiscretionary()), 20) // Company Discretionary Data (20)
                + padRight(config.companyId(), 10)          // Company Identification (10)
                + padRight(config.entryClass().name(), 3)   // Standard Entry Class Code (3)
                + padRight(config.companyEntryDescription(), 10)  // Company Entry Description (10)
                + descDate                                  // Company Descriptive Date (6)
                + config.effectiveEntryDate().format(NACHA_DATE)  // Effective Entry Date (6)
                + "   "                                     // Settlement Date (3) — filled by ACH operator
                + "1"                                       // Originator Status Code (1)
                + padRight(config.originatingDfi().raw().substring(0, 8), 8) // Originating DFI (8)
                + padLeft(String.valueOf(batchNumber), 7, '0')  // Batch Number (7)
                + '\n';
    }

    private String buildEntryDetail(EntryDetail entry, String traceNumber) {
        // Record Type 6: Entry Detail
        return "6"                                          // Record Type Code
                + padLeft(String.valueOf(entry.transactionCode().code()), 2, '0') // Transaction Code (2)
                + entry.receivingDfi().raw().substring(0, 8)    // Receiving DFI Routing (8)
                + entry.receivingDfi().raw().charAt(8)          // Check Digit (1)
                + padRight(entry.dfiAccountNumber(), 17)    // DFI Account Number (17)
                + padLeft(entry.amount().movePointRight(2).toBigInteger().toString(), 10, '0') // Amount (10)
                + padRight(nvl(entry.individualId()), 15)   // Individual ID Number (15)
                + padRight(entry.individualName(), 22)      // Individual Name (22)
                + padRight(nvl(entry.discretionaryData()), 2) // Discretionary Data (2)
                + (entry.hasAddenda() ? "1" : "0")          // Addenda Record Indicator (1)
                + traceNumber                               // Trace Number (15)
                + '\n';
    }

    private String buildAddendaRecord(String addendaInfo, String traceNumber) {
        // Record Type 7: Addenda
        return "7"                                          // Record Type Code
                + "05"                                      // Addenda Type Code (2)
                + padRight(addendaInfo, 80)                 // Payment Related Information (80)
                + "0001"                                    // Addenda Sequence Number (4)
                + padLeft(traceNumber.substring(traceNumber.length() - 7), 7, '0') // Entry Detail Sequence (7)
                + '\n';
    }

    private String buildBatchControl(BatchConfig config, int batchNumber,
                                      int entryAddendaCount, long entryHash,
                                      long debitAmount, long creditAmount) {
        long truncatedHash = entryHash % 10_000_000_000L;
        // Record Type 8: Batch Control
        return "8"                                          // Record Type Code
                + String.valueOf(config.serviceClass().code())  // Service Class Code (3)
                + padLeft(String.valueOf(entryAddendaCount), 6, '0') // Entry/Addenda Count (6)
                + padLeft(String.valueOf(truncatedHash), 10, '0')    // Entry Hash (10)
                + padLeft(String.valueOf(debitAmount), 12, '0')      // Total Debit Amount (12)
                + padLeft(String.valueOf(creditAmount), 12, '0')     // Total Credit Amount (12)
                + padRight(config.companyId(), 10)          // Company Identification (10)
                + padRight("", 19)                          // Message Authentication Code (19)
                + padRight("", 6)                           // Reserved (6)
                + padRight(config.originatingDfi().raw().substring(0, 8), 8) // Originating DFI (8)
                + padLeft(String.valueOf(batchNumber), 7, '0')  // Batch Number (7)
                + '\n';
    }

    private String buildFileControl(int batchCount, int entryAddendaCount,
                                     long entryHash, long debitAmount, long creditAmount) {
        long truncatedHash = entryHash % 10_000_000_000L;
        int blockCount = (int) Math.ceil((double) (countLines() + 1) / BLOCKING_FACTOR);
        // Record Type 9: File Control
        return "9"                                          // Record Type Code
                + padLeft(String.valueOf(batchCount), 6, '0')         // Batch Count (6)
                + padLeft(String.valueOf(blockCount), 6, '0')         // Block Count (6)
                + padLeft(String.valueOf(entryAddendaCount), 8, '0')  // Entry/Addenda Count (8)
                + padLeft(String.valueOf(truncatedHash), 10, '0')     // Entry Hash (10)
                + padLeft(String.valueOf(debitAmount), 12, '0')       // Total Debit Amount (12)
                + padLeft(String.valueOf(creditAmount), 12, '0')      // Total Credit Amount (12)
                + padRight("", 39)                          // Reserved (39)
                + '\n';
    }

    private int countLines() {
        int lines = 1; // file header
        for (int i = 0; i < batches.size(); i++) {
            lines += 1; // batch header
            var entries = batchEntries.get(i);
            for (var entry : entries) {
                lines += 1; // entry detail
                if (entry.hasAddenda()) lines += 1;
            }
            lines += 1; // batch control
        }
        return lines;
    }

    private int countRecords(String content) {
        return (int) content.lines().count();
    }

    private static String padRight(String s, int width) {
        return padRight(s, width, ' ');
    }

    private static String padRight(String s, int width, char padChar) {
        if (s.length() >= width) return s.substring(0, width);
        return s + String.valueOf(padChar).repeat(width - s.length());
    }

    private static String padLeft(String s, int width, char padChar) {
        if (s.length() >= width) return s.substring(s.length() - width);
        return String.valueOf(padChar).repeat(width - s.length()) + s;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
