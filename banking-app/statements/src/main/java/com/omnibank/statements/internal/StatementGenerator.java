package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generates itemized account statements for a billing cycle.
 *
 * <p>A statement is assembled in a fixed five-section layout:
 * <pre>
 *   1. Header     — institution metadata, account holder block, cycle range
 *   2. Summary    — opening balance, closing balance, totals per category
 *   3. Transactions — chronological list of postings with running balance
 *   4. Fees and interest — itemized service charges and APY paid
 *   5. Footer     — Reg DD disclosures, holds summary, contact block
 * </pre>
 *
 * <p>Rendering is separated from assembly via a pluggable {@link StatementFormatter}
 * strategy — the same {@link StatementContent} can be produced as plain text,
 * HTML email, or (stub) PDF. A deterministic content hash is computed over the
 * assembled sections so downstream archiving and fraud flagging can cheaply
 * compare statements across runs.
 *
 * <p>The generator is intentionally pure: it takes inputs, assembles a document,
 * and returns it. Persistence, delivery, and archival are handled elsewhere so
 * this class remains easy to unit-test and free of side effects.
 */
public class StatementGenerator {

    private static final Logger log = LoggerFactory.getLogger(StatementGenerator.class);

    /** Default institution name surfaced in the header when none is injected. */
    public static final String DEFAULT_INSTITUTION = "Omnibank, N.A.";

    /** Maximum transactions that a single statement may itemize. */
    public static final int MAX_TRANSACTIONS_PER_STATEMENT = 10_000;

    private static final DateTimeFormatter CYCLE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /** Output format a generator can emit. Each format is wired through a formatter. */
    public enum Format {
        PLAIN_TEXT,
        HTML,
        PDF
    }

    /** Type of line item on a statement. Drives grouping in the summary section. */
    public enum LineType {
        DEPOSIT,
        WITHDRAWAL,
        CHECK,
        TRANSFER,
        FEE,
        INTEREST_EARNED,
        INTEREST_CHARGED,
        ADJUSTMENT,
        HOLD,
        RELEASE
    }

    /**
     * Immutable content block for a single transaction row on the statement.
     */
    public record StatementLineItem(
            LocalDate postedOn,
            String description,
            Money amount,
            LineType type,
            String referenceId
    ) {
        public StatementLineItem {
            Objects.requireNonNull(postedOn, "postedOn");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(referenceId, "referenceId");
        }

        /** True if this line increases the account balance. */
        public boolean isCredit() {
            return type == LineType.DEPOSIT
                    || type == LineType.INTEREST_EARNED
                    || type == LineType.RELEASE
                    || (type == LineType.ADJUSTMENT && amount.isPositive());
        }
    }

    /** Hold visible on a statement (funds reserved against available balance). */
    public record HoldSummary(String description, Money amount, LocalDate placedOn, LocalDate expiresOn) {
        public HoldSummary {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(placedOn, "placedOn");
            Objects.requireNonNull(expiresOn, "expiresOn");
        }
    }

    /** Identity and routing block for the account holder printed in the header. */
    public record StatementHeader(
            String institutionName,
            AccountNumber account,
            CustomerId customerId,
            String accountHolderName,
            String mailingAddress,
            LocalDate cycleStart,
            LocalDate cycleEnd,
            String productName
    ) {
        public StatementHeader {
            Objects.requireNonNull(institutionName, "institutionName");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(accountHolderName, "accountHolderName");
            Objects.requireNonNull(mailingAddress, "mailingAddress");
            Objects.requireNonNull(cycleStart, "cycleStart");
            Objects.requireNonNull(cycleEnd, "cycleEnd");
            Objects.requireNonNull(productName, "productName");
            if (cycleEnd.isBefore(cycleStart)) {
                throw new IllegalArgumentException("cycleEnd before cycleStart");
            }
        }
    }

    /** Aggregate financial totals computed from the line items, cached on the content. */
    public record StatementSummary(
            Money openingBalance,
            Money closingBalance,
            Money totalDeposits,
            Money totalWithdrawals,
            Money totalFees,
            Money totalInterestEarned,
            int transactionCount
    ) {
        public StatementSummary {
            Objects.requireNonNull(openingBalance, "openingBalance");
            Objects.requireNonNull(closingBalance, "closingBalance");
            Objects.requireNonNull(totalDeposits, "totalDeposits");
            Objects.requireNonNull(totalWithdrawals, "totalWithdrawals");
            Objects.requireNonNull(totalFees, "totalFees");
            Objects.requireNonNull(totalInterestEarned, "totalInterestEarned");
        }
    }

    /** Fully-assembled statement content, pre-formatting. */
    public record StatementContent(
            String statementId,
            StatementHeader header,
            StatementSummary summary,
            List<StatementLineItem> lineItems,
            List<HoldSummary> holds,
            Instant generatedAt,
            String contentHash
    ) {
        public StatementContent {
            Objects.requireNonNull(statementId, "statementId");
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(lineItems, "lineItems");
            Objects.requireNonNull(holds, "holds");
            Objects.requireNonNull(generatedAt, "generatedAt");
            Objects.requireNonNull(contentHash, "contentHash");
            lineItems = List.copyOf(lineItems);
            holds = List.copyOf(holds);
        }
    }

    /** Rendered artifact ready for delivery (bytes + format). */
    public record RenderedStatement(StatementContent content, Format format, byte[] payload) {
        public RenderedStatement {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /** Input bundle — caller hands the generator everything needed, with no callbacks. */
    public record GenerationRequest(
            StatementHeader header,
            Money openingBalance,
            List<StatementLineItem> lineItems,
            List<HoldSummary> holds
    ) {
        public GenerationRequest {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(openingBalance, "openingBalance");
            Objects.requireNonNull(lineItems, "lineItems");
            Objects.requireNonNull(holds, "holds");
        }
    }

    /** Strategy for rendering {@link StatementContent} into bytes. */
    public interface StatementFormatter {
        Format format();
        byte[] render(StatementContent content);
    }

    private final Clock clock;
    private final Map<Format, StatementFormatter> formatters = new EnumMap<>(Format.class);

    public StatementGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        // Default formatter set. Callers may register additional formatters
        // (e.g. an XHTML reskin) via registerFormatter.
        registerFormatter(new PlainTextFormatter());
        registerFormatter(new HtmlFormatter());
        registerFormatter(new PdfStubFormatter());
    }

    /** Install or replace a formatter for a particular output format. */
    public void registerFormatter(StatementFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        formatters.put(formatter.format(), formatter);
    }

    /**
     * Assemble the in-memory {@link StatementContent} for a cycle. No bytes are
     * produced here — the caller composes with {@link #render}.
     */
    public StatementContent generate(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.lineItems().size() > MAX_TRANSACTIONS_PER_STATEMENT) {
            throw new IllegalArgumentException(
                    "Too many transactions (%d) exceeds max %d"
                            .formatted(request.lineItems().size(), MAX_TRANSACTIONS_PER_STATEMENT));
        }

        CurrencyCode currency = request.openingBalance().currency();
        List<StatementLineItem> ordered = new ArrayList<>(request.lineItems());
        Collections.sort(ordered, (a, b) -> a.postedOn().compareTo(b.postedOn()));

        // Validate that every line item is in the same currency as the opening balance.
        for (StatementLineItem item : ordered) {
            if (item.amount().currency() != currency) {
                throw new IllegalArgumentException(
                        "Line item currency %s does not match statement currency %s"
                                .formatted(item.amount().currency(), currency));
            }
        }

        StatementSummary summary = summarize(request.openingBalance(), ordered, currency);
        Instant generatedAt = clock.instant();
        String statementId = generateStatementId(request.header(), generatedAt);
        String hash = computeHash(statementId, request.header(), summary, ordered);

        var content = new StatementContent(
                statementId,
                request.header(),
                summary,
                ordered,
                List.copyOf(request.holds()),
                generatedAt,
                hash);

        log.info("Generated statement id={} account={} cycle={}..{} lines={} closing={}",
                statementId, request.header().account(),
                request.header().cycleStart(), request.header().cycleEnd(),
                ordered.size(), summary.closingBalance());

        return content;
    }

    /**
     * Render {@link StatementContent} to a particular output format using the
     * registered {@link StatementFormatter}.
     */
    public RenderedStatement render(StatementContent content, Format format) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(format, "format");
        StatementFormatter formatter = formatters.get(format);
        if (formatter == null) {
            throw new IllegalArgumentException("No formatter registered for format: " + format);
        }
        byte[] bytes = formatter.render(content);
        return new RenderedStatement(content, format, bytes);
    }

    /** Convenience: assemble and render in one call. */
    public RenderedStatement generateAndRender(GenerationRequest request, Format format) {
        return render(generate(request), format);
    }

    /** True if a formatter is registered for the given format. */
    public boolean hasFormatter(Format format) {
        return formatters.containsKey(format);
    }

    // ── internals ────────────────────────────────────────────────────────

    private StatementSummary summarize(Money opening, List<StatementLineItem> items, CurrencyCode currency) {
        Money deposits = Money.zero(currency);
        Money withdrawals = Money.zero(currency);
        Money fees = Money.zero(currency);
        Money interestEarned = Money.zero(currency);
        Money running = opening;

        for (StatementLineItem item : items) {
            switch (item.type()) {
                case DEPOSIT, TRANSFER -> {
                    if (item.isCredit()) {
                        deposits = deposits.plus(item.amount().abs());
                        running = running.plus(item.amount().abs());
                    } else {
                        withdrawals = withdrawals.plus(item.amount().abs());
                        running = running.minus(item.amount().abs());
                    }
                }
                case WITHDRAWAL, CHECK -> {
                    withdrawals = withdrawals.plus(item.amount().abs());
                    running = running.minus(item.amount().abs());
                }
                case FEE, INTEREST_CHARGED -> {
                    fees = fees.plus(item.amount().abs());
                    running = running.minus(item.amount().abs());
                }
                case INTEREST_EARNED -> {
                    interestEarned = interestEarned.plus(item.amount().abs());
                    running = running.plus(item.amount().abs());
                }
                case ADJUSTMENT -> {
                    if (item.amount().isNegative()) {
                        running = running.minus(item.amount().abs());
                        withdrawals = withdrawals.plus(item.amount().abs());
                    } else {
                        running = running.plus(item.amount().abs());
                        deposits = deposits.plus(item.amount().abs());
                    }
                }
                case HOLD, RELEASE -> {
                    // Holds do not change the statement balance — they surface
                    // in the holds section of the footer only.
                }
            }
        }

        return new StatementSummary(
                opening,
                running,
                deposits,
                withdrawals,
                fees,
                interestEarned,
                items.size());
    }

    private String generateStatementId(StatementHeader header, Instant generatedAt) {
        return "STMT-%s-%s-%s".formatted(
                header.account().raw(),
                header.cycleEnd().format(DateTimeFormatter.BASIC_ISO_DATE),
                UUID.nameUUIDFromBytes(
                        (header.account().raw() + header.cycleEnd() + generatedAt)
                                .getBytes(StandardCharsets.UTF_8))
                        .toString().substring(0, 8));
    }

    private String computeHash(String statementId, StatementHeader header,
                               StatementSummary summary, List<StatementLineItem> items) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(statementId.getBytes(StandardCharsets.UTF_8));
            sha.update(header.account().raw().getBytes(StandardCharsets.UTF_8));
            sha.update(header.cycleStart().toString().getBytes(StandardCharsets.UTF_8));
            sha.update(header.cycleEnd().toString().getBytes(StandardCharsets.UTF_8));
            sha.update(summary.openingBalance().amount().toPlainString().getBytes(StandardCharsets.UTF_8));
            sha.update(summary.closingBalance().amount().toPlainString().getBytes(StandardCharsets.UTF_8));
            for (StatementLineItem i : items) {
                sha.update(i.postedOn().toString().getBytes(StandardCharsets.UTF_8));
                sha.update(i.referenceId().getBytes(StandardCharsets.UTF_8));
                sha.update(i.amount().amount().toPlainString().getBytes(StandardCharsets.UTF_8));
                sha.update(i.type().name().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported JVM — this should never fire.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── formatters ───────────────────────────────────────────────────────

    static final class PlainTextFormatter implements StatementFormatter {
        @Override
        public Format format() {
            return Format.PLAIN_TEXT;
        }

        @Override
        public byte[] render(StatementContent content) {
            StringBuilder sb = new StringBuilder(4096);
            var h = content.header();
            sb.append(h.institutionName()).append('\n');
            sb.append("Statement for ").append(h.accountHolderName()).append('\n');
            sb.append("Account: ").append(h.account().raw()).append('\n');
            sb.append("Product: ").append(h.productName()).append('\n');
            sb.append("Period:  ")
                    .append(h.cycleStart().format(CYCLE_FMT))
                    .append(" through ")
                    .append(h.cycleEnd().format(CYCLE_FMT))
                    .append('\n');
            sb.append("Address: ").append(h.mailingAddress()).append('\n');
            sb.append("----------------------------------------\n");

            var s = content.summary();
            sb.append("Opening Balance: ").append(s.openingBalance()).append('\n');
            sb.append("Total Deposits:  ").append(s.totalDeposits()).append('\n');
            sb.append("Total Withdrawals: ").append(s.totalWithdrawals()).append('\n');
            sb.append("Total Fees:      ").append(s.totalFees()).append('\n');
            sb.append("Interest Earned: ").append(s.totalInterestEarned()).append('\n');
            sb.append("Closing Balance: ").append(s.closingBalance()).append('\n');
            sb.append("Transactions:    ").append(s.transactionCount()).append('\n');
            sb.append("----------------------------------------\n");

            sb.append("TRANSACTIONS\n");
            for (StatementLineItem item : content.lineItems()) {
                sb.append(item.postedOn())
                        .append("  ")
                        .append(String.format(Locale.US, "%-10s", item.type()))
                        .append("  ")
                        .append(item.amount())
                        .append("  ")
                        .append(item.description())
                        .append(" [ref=").append(item.referenceId()).append(']')
                        .append('\n');
            }
            sb.append("----------------------------------------\n");

            if (!content.holds().isEmpty()) {
                sb.append("HOLDS\n");
                for (HoldSummary hold : content.holds()) {
                    sb.append("  ").append(hold.amount()).append(" - ").append(hold.description())
                            .append(" (placed ").append(hold.placedOn())
                            .append(", expires ").append(hold.expiresOn()).append(")\n");
                }
                sb.append("----------------------------------------\n");
            }

            sb.append("Statement ID: ").append(content.statementId()).append('\n');
            sb.append("Generated:    ").append(content.generatedAt()).append('\n');
            sb.append("APY and fee disclosures per Regulation DD are available on request.\n");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static final class HtmlFormatter implements StatementFormatter {
        @Override
        public Format format() {
            return Format.HTML;
        }

        @Override
        public byte[] render(StatementContent content) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>Statement ")
                    .append(esc(content.statementId())).append("</title></head><body>");
            var h = content.header();
            sb.append("<h1>").append(esc(h.institutionName())).append("</h1>");
            sb.append("<section class=\"header\"><p><strong>")
                    .append(esc(h.accountHolderName())).append("</strong><br/>")
                    .append(esc(h.mailingAddress())).append("</p>");
            sb.append("<p>Account: ").append(esc(h.account().raw())).append("<br/>")
                    .append("Product: ").append(esc(h.productName())).append("<br/>")
                    .append("Period: ").append(h.cycleStart()).append(" through ")
                    .append(h.cycleEnd()).append("</p></section>");

            var s = content.summary();
            sb.append("<section class=\"summary\"><table>");
            row(sb, "Opening balance", s.openingBalance());
            row(sb, "Total deposits", s.totalDeposits());
            row(sb, "Total withdrawals", s.totalWithdrawals());
            row(sb, "Total fees", s.totalFees());
            row(sb, "Interest earned", s.totalInterestEarned());
            row(sb, "Closing balance", s.closingBalance());
            sb.append("</table></section>");

            sb.append("<section class=\"transactions\"><h2>Transactions</h2><table>");
            sb.append("<tr><th>Date</th><th>Type</th><th>Amount</th><th>Description</th></tr>");
            for (StatementLineItem item : content.lineItems()) {
                sb.append("<tr><td>").append(item.postedOn()).append("</td>");
                sb.append("<td>").append(item.type()).append("</td>");
                sb.append("<td>").append(item.amount()).append("</td>");
                sb.append("<td>").append(esc(item.description())).append("</td></tr>");
            }
            sb.append("</table></section>");

            if (!content.holds().isEmpty()) {
                sb.append("<section class=\"holds\"><h2>Holds</h2><ul>");
                for (HoldSummary hold : content.holds()) {
                    sb.append("<li>").append(hold.amount()).append(" - ")
                            .append(esc(hold.description()))
                            .append(" (expires ").append(hold.expiresOn()).append(")</li>");
                }
                sb.append("</ul></section>");
            }
            sb.append("<footer><p>Statement ID: ").append(content.statementId()).append("</p>");
            sb.append("<p>Generated: ").append(content.generatedAt()).append("</p></footer>");
            sb.append("</body></html>");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static void row(StringBuilder sb, String label, Money m) {
            sb.append("<tr><td>").append(label).append("</td><td>").append(m).append("</td></tr>");
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }

    /**
     * Minimal PDF stub. The enterprise system delegates to a proper PDF
     * renderer (iText, PDFBox) in production. For testing and benchmark
     * purposes we emit a placeholder byte stream that preserves the content
     * hash so archival comparisons remain valid without pulling a heavy
     * transitive dependency.
     */
    static final class PdfStubFormatter implements StatementFormatter {
        private static final String PDF_MAGIC = "%PDF-1.4\n";

        @Override
        public Format format() {
            return Format.PDF;
        }

        @Override
        public byte[] render(StatementContent content) {
            StringBuilder sb = new StringBuilder();
            sb.append(PDF_MAGIC);
            sb.append("% Statement ").append(content.statementId()).append('\n');
            sb.append("% Hash ").append(content.contentHash()).append('\n');
            sb.append("% Account ").append(content.header().account().raw()).append('\n');
            sb.append("% ClosingBalance ")
                    .append(content.summary().closingBalance().amount().toPlainString()).append('\n');
            // A real PDF stream would embed the full body here; stub keeps bytes minimal.
            BigDecimal zero = BigDecimal.ZERO;
            sb.append("% EOF ").append(zero).append('\n');
            sb.append("%%EOF\n");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
