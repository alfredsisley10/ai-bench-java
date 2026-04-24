package com.omnibank.compliance.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates and tracks regulatory reports required under BSA/AML, FATCA,
 * and CRS regulations. Each report type has specific formatting requirements,
 * filing deadlines, and submission channels.
 *
 * <p>Report types:
 * <ul>
 *   <li><b>CTR (Currency Transaction Report):</b> Required for cash transactions
 *       aggregating over $10,000 per customer per business day (31 CFR 1010.311).
 *       Filed with FinCEN within 15 calendar days.</li>
 *   <li><b>SAR (Suspicious Activity Report):</b> Filed when suspicious activity
 *       is detected, typically involving $5,000+ (31 CFR 1020.320).
 *       Filed within 30 calendar days of detection.</li>
 *   <li><b>FATCA (Foreign Account Tax Compliance Act):</b> Annual reporting of
 *       US persons' accounts held at foreign financial institutions, and foreign
 *       persons' accounts at US institutions (IRC 1471-1474).</li>
 *   <li><b>CRS (Common Reporting Standard):</b> OECD automatic exchange of
 *       financial account information between participating jurisdictions.</li>
 * </ul>
 *
 * <p>Each report goes through a lifecycle: DRAFT -> VALIDATED -> SUBMITTED ->
 * ACKNOWLEDGED -> ACCEPTED (or REJECTED with error details).
 */
public class RegulatoryReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(RegulatoryReportGenerator.class);

    /** Filing deadlines in calendar days from triggering event. */
    private static final int CTR_FILING_DEADLINE_DAYS = 15;
    private static final int SAR_FILING_DEADLINE_DAYS = 30;
    private static final int SAR_INITIAL_FILING_DAYS = 30;

    /** CTR threshold: aggregate cash transactions exceeding $10,000 per day. */
    private static final Money CTR_THRESHOLD = Money.of("10000.00", CurrencyCode.USD);

    /** SAR minimum amount for mandatory filing. */
    private static final Money SAR_MINIMUM_AMOUNT = Money.of("5000.00", CurrencyCode.USD);

    /** FinCEN BSA E-Filing batch ID format. */
    private static final DateTimeFormatter BATCH_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    enum ReportType { CTR, SAR, FATCA_8966, CRS }

    enum ReportStatus {
        DRAFT, VALIDATED, SUBMISSION_PENDING, SUBMITTED,
        ACKNOWLEDGED, ACCEPTED, REJECTED, AMENDED
    }

    record RegulatoryReport(
            UUID reportId,
            ReportType reportType,
            ReportStatus status,
            CustomerId customer,
            AccountNumber primaryAccount,
            Money transactionAmount,
            LocalDate triggerDate,
            LocalDate filingDeadline,
            Instant createdAt,
            Instant submittedAt,
            String batchId,
            String filingConfirmation,
            String xmlPayload,
            List<ValidationError> validationErrors,
            String narrative
    ) {
        public RegulatoryReport {
            Objects.requireNonNull(reportId, "reportId");
            Objects.requireNonNull(reportType, "reportType");
            Objects.requireNonNull(status, "status");
            validationErrors = validationErrors != null
                    ? List.copyOf(validationErrors) : List.of();
        }
    }

    record ValidationError(String field, String code, String message) {}

    record CtrData(
            CustomerId customer,
            String customerName,
            String customerSsn,
            AccountNumber account,
            LocalDate transactionDate,
            Money cashIn,
            Money cashOut,
            Money totalCash,
            String tellerEmployeeId,
            String branchId
    ) {}

    record SarData(
            CustomerId customer,
            String customerName,
            AccountNumber account,
            Money suspiciousAmount,
            LocalDate activityDateFrom,
            LocalDate activityDateTo,
            String narrative,
            List<String> typologyCodes,
            UUID relatedAlertId
    ) {}

    record FatcaAccountData(
            CustomerId accountHolder,
            String tin,
            String accountHolderCountry,
            AccountNumber account,
            Money yearEndBalance,
            Money totalCredits,
            int reportingYear
    ) {}

    record ReportEvent(UUID eventId, Instant occurredAt, UUID reportId,
                       ReportType reportType, ReportStatus status) implements DomainEvent {
        @Override
        public String eventType() {
            return "compliance.report." + reportType.name().toLowerCase();
        }
    }

    /** In-memory report store. */
    private final ConcurrentHashMap<UUID, RegulatoryReport> reports = new ConcurrentHashMap<>();

    private final EventBus events;
    private final Clock clock;

    public RegulatoryReportGenerator(EventBus events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Generate a CTR for cash transactions exceeding $10,000.
     * Creates the report in DRAFT status and runs validation.
     */
    public RegulatoryReport generateCtr(CtrData data) {
        Objects.requireNonNull(data, "data");

        if (data.totalCash().compareTo(CTR_THRESHOLD) < 0) {
            throw new IllegalArgumentException(
                    "CTR not required: total cash " + data.totalCash() + " below threshold");
        }

        LocalDate deadline = data.transactionDate().plusDays(CTR_FILING_DEADLINE_DAYS);
        String xml = formatCtrXml(data);

        RegulatoryReport report = new RegulatoryReport(
                UUID.randomUUID(), ReportType.CTR, ReportStatus.DRAFT,
                data.customer(), data.account(), data.totalCash(),
                data.transactionDate(), deadline, Timestamp.now(clock),
                null, null, null, xml, List.of(),
                "CTR for cash transactions on " + data.transactionDate()
        );

        report = validate(report);
        reports.put(report.reportId(), report);
        publishEvent(report);

        log.info("CTR generated: id={}, customer={}, amount={}, deadline={}",
                report.reportId(), data.customer(), data.totalCash(), deadline);
        return report;
    }

    /**
     * Generate a SAR from a confirmed AML alert.
     */
    public RegulatoryReport generateSar(SarData data) {
        Objects.requireNonNull(data, "data");

        LocalDate triggerDate = data.activityDateTo() != null
                ? data.activityDateTo() : LocalDate.now(clock);
        LocalDate deadline = triggerDate.plusDays(SAR_FILING_DEADLINE_DAYS);
        String xml = formatSarXml(data);

        RegulatoryReport report = new RegulatoryReport(
                UUID.randomUUID(), ReportType.SAR, ReportStatus.DRAFT,
                data.customer(), data.account(), data.suspiciousAmount(),
                triggerDate, deadline, Timestamp.now(clock),
                null, null, null, xml, List.of(),
                data.narrative()
        );

        report = validate(report);
        reports.put(report.reportId(), report);
        publishEvent(report);

        log.info("SAR generated: id={}, customer={}, amount={}, typologies={}",
                report.reportId(), data.customer(), data.suspiciousAmount(),
                data.typologyCodes());
        return report;
    }

    /**
     * Generate FATCA Form 8966 for a reportable account.
     */
    public RegulatoryReport generateFatca(FatcaAccountData data) {
        Objects.requireNonNull(data, "data");

        LocalDate deadline = LocalDate.of(data.reportingYear() + 1, 3, 31);
        String xml = formatFatcaXml(data);

        RegulatoryReport report = new RegulatoryReport(
                UUID.randomUUID(), ReportType.FATCA_8966, ReportStatus.DRAFT,
                data.accountHolder(), data.account(), data.yearEndBalance(),
                LocalDate.of(data.reportingYear(), 12, 31), deadline,
                Timestamp.now(clock), null, null, null, xml, List.of(),
                "FATCA 8966 for reporting year " + data.reportingYear()
        );

        report = validate(report);
        reports.put(report.reportId(), report);
        publishEvent(report);

        log.info("FATCA report generated: id={}, accountHolder={}, year={}",
                report.reportId(), data.accountHolder(), data.reportingYear());
        return report;
    }

    /**
     * Submit a validated report to the appropriate regulatory authority.
     */
    public RegulatoryReport submit(UUID reportId) {
        RegulatoryReport report = requireReport(reportId);

        if (report.status() != ReportStatus.VALIDATED) {
            throw new IllegalStateException(
                    "Report must be VALIDATED before submission; current status: " + report.status());
        }

        String batchId = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + report.reportType().name() + "-" + reportId.toString().substring(0, 8);

        RegulatoryReport submitted = new RegulatoryReport(
                report.reportId(), report.reportType(), ReportStatus.SUBMITTED,
                report.customer(), report.primaryAccount(), report.transactionAmount(),
                report.triggerDate(), report.filingDeadline(), report.createdAt(),
                Timestamp.now(clock), batchId, null, report.xmlPayload(),
                report.validationErrors(), report.narrative()
        );

        reports.put(reportId, submitted);
        publishEvent(submitted);

        log.info("Report submitted: id={}, type={}, batchId={}",
                reportId, report.reportType(), batchId);
        return submitted;
    }

    /**
     * Record acknowledgment from the regulatory authority.
     */
    public RegulatoryReport recordAcknowledgment(UUID reportId, String confirmationNumber) {
        RegulatoryReport report = requireReport(reportId);

        RegulatoryReport acknowledged = new RegulatoryReport(
                report.reportId(), report.reportType(), ReportStatus.ACKNOWLEDGED,
                report.customer(), report.primaryAccount(), report.transactionAmount(),
                report.triggerDate(), report.filingDeadline(), report.createdAt(),
                report.submittedAt(), report.batchId(), confirmationNumber,
                report.xmlPayload(), report.validationErrors(), report.narrative()
        );

        reports.put(reportId, acknowledged);
        publishEvent(acknowledged);
        return acknowledged;
    }

    /**
     * Check for reports approaching their filing deadline.
     * Runs daily and alerts compliance officers of approaching deadlines.
     */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "America/New_York")
    public List<RegulatoryReport> checkApproachingDeadlines() {
        LocalDate today = LocalDate.now(clock);
        LocalDate warningHorizon = today.plusDays(5);

        List<RegulatoryReport> approaching = reports.values().stream()
                .filter(r -> r.status() == ReportStatus.DRAFT
                        || r.status() == ReportStatus.VALIDATED)
                .filter(r -> r.filingDeadline() != null)
                .filter(r -> !r.filingDeadline().isAfter(warningHorizon))
                .toList();

        for (RegulatoryReport report : approaching) {
            long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(
                    today, report.filingDeadline());
            log.warn("Report {} ({}) deadline in {} days: customer={}, type={}",
                    report.reportId(), report.reportType(), daysRemaining,
                    report.customer(), report.reportType());
        }

        return approaching;
    }

    /**
     * List all reports for a customer, optionally filtered by type and status.
     */
    public List<RegulatoryReport> getReportsForCustomer(CustomerId customer,
                                                          ReportType type,
                                                          ReportStatus status) {
        return reports.values().stream()
                .filter(r -> customer == null || customer.equals(r.customer()))
                .filter(r -> type == null || type == r.reportType())
                .filter(r -> status == null || status == r.status())
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    private RegulatoryReport validate(RegulatoryReport report) {
        List<ValidationError> errors = new ArrayList<>();

        if (report.customer() == null) {
            errors.add(new ValidationError("customer", "REQUIRED", "Customer ID is required"));
        }
        if (report.primaryAccount() == null) {
            errors.add(new ValidationError("primaryAccount", "REQUIRED",
                    "Primary account is required"));
        }
        if (report.xmlPayload() == null || report.xmlPayload().isBlank()) {
            errors.add(new ValidationError("xmlPayload", "REQUIRED",
                    "XML payload must be generated"));
        }

        // Type-specific validation
        switch (report.reportType()) {
            case CTR -> {
                if (report.transactionAmount().compareTo(CTR_THRESHOLD) < 0) {
                    errors.add(new ValidationError("transactionAmount", "BELOW_THRESHOLD",
                            "CTR amount below $10,000 threshold"));
                }
            }
            case SAR -> {
                if (report.narrative() == null || report.narrative().length() < 50) {
                    errors.add(new ValidationError("narrative", "INSUFFICIENT",
                            "SAR narrative must be at least 50 characters"));
                }
            }
            case FATCA_8966, CRS -> {
                // FATCA/CRS require TIN validation — simplified here
            }
        }

        ReportStatus newStatus = errors.isEmpty() ? ReportStatus.VALIDATED : ReportStatus.DRAFT;
        return new RegulatoryReport(
                report.reportId(), report.reportType(), newStatus,
                report.customer(), report.primaryAccount(), report.transactionAmount(),
                report.triggerDate(), report.filingDeadline(), report.createdAt(),
                report.submittedAt(), report.batchId(), report.filingConfirmation(),
                report.xmlPayload(), errors, report.narrative()
        );
    }

    private String formatCtrXml(CtrData data) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <CTR xmlns="https://www.fincen.gov/ctr">
                  <Activity>
                    <DateOfTransaction>%s</DateOfTransaction>
                    <TotalCashIn>%s</TotalCashIn>
                    <TotalCashOut>%s</TotalCashOut>
                  </Activity>
                  <TransactorInfo>
                    <AccountNumber>%s</AccountNumber>
                    <CustomerId>%s</CustomerId>
                  </TransactorInfo>
                  <FinancialInstitution>
                    <Name>Omnibank NA</Name>
                    <RSSD>9999999</RSSD>
                  </FinancialInstitution>
                </CTR>
                """.formatted(
                data.transactionDate(),
                data.cashIn().amount().toPlainString(),
                data.cashOut().amount().toPlainString(),
                data.account().raw(),
                data.customer().value()
        );
    }

    private String formatSarXml(SarData data) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SAR xmlns="https://www.fincen.gov/sar">
                  <Activity>
                    <DateRangeFrom>%s</DateRangeFrom>
                    <DateRangeTo>%s</DateRangeTo>
                    <SuspiciousAmount>%s</SuspiciousAmount>
                  </Activity>
                  <Subject>
                    <AccountNumber>%s</AccountNumber>
                    <CustomerId>%s</CustomerId>
                  </Subject>
                  <Narrative>%s</Narrative>
                  <FinancialInstitution>
                    <Name>Omnibank NA</Name>
                    <RSSD>9999999</RSSD>
                  </FinancialInstitution>
                </SAR>
                """.formatted(
                data.activityDateFrom(),
                data.activityDateTo(),
                data.suspiciousAmount().amount().toPlainString(),
                data.account().raw(),
                data.customer().value(),
                escapeXml(data.narrative())
        );
    }

    private String formatFatcaXml(FatcaAccountData data) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <FATCA xmlns="urn:oecd:ties:fatca:v2">
                  <ReportingGroup>
                    <AccountReport>
                      <AccountNumber>%s</AccountNumber>
                      <AccountHolderTIN>%s</AccountHolderTIN>
                      <AccountBalance>%s</AccountBalance>
                      <TotalCredits>%s</TotalCredits>
                      <ReportingYear>%d</ReportingYear>
                    </AccountReport>
                  </ReportingGroup>
                  <ReportingFI>
                    <Name>Omnibank NA</Name>
                    <GIIN>98Q96B.00000.LE.840</GIIN>
                  </ReportingFI>
                </FATCA>
                """.formatted(
                data.account().raw(),
                data.tin(),
                data.yearEndBalance().amount().toPlainString(),
                data.totalCredits().amount().toPlainString(),
                data.reportingYear()
        );
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private RegulatoryReport requireReport(UUID reportId) {
        RegulatoryReport report = reports.get(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        return report;
    }

    private void publishEvent(RegulatoryReport report) {
        events.publish(new ReportEvent(UUID.randomUUID(), Timestamp.now(clock),
                report.reportId(), report.reportType(), report.status()));
    }
}
