package com.aibench.codegen;

import com.aibench.codegen.ProductVariantGenerator.ProductSpec;

/**
 * String templates for the generated product-variant files.
 *
 * <p>Each method returns a fully-formed Java source file as a String. Keep the
 * templates deliberate — they produce real enterprise-flavored code that
 * compiles, passes its own tests, and exercises branches inside the AppMap
 * traces. Don't slim them down for brevity — the point of generation is
 * volume at realistic shape.
 */
final class Templates {

    private Templates() {}

    static String buildGradle() {
        return """
                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testImplementation("org.assertj:assertj-core")
                }
                """;
    }

    static String packageInfo(String pkg, ProductSpec s) {
        return """
                /**
                 * %HUMAN% — generated product variant module.
                 *
                 * <p>Category: %CATEGORY%<br>
                 * Target segment: %SEGMENT%<br>
                 * Jurisdiction: %JURIS%<br>
                 * Base rate: %RATE%
                 *
                 * <p>This module was produced by
                 * {@code com.aibench.codegen.ProductVariantGenerator}. Do not hand-edit —
                 * rerun the generator to modify.
                 */
                package %PKG%;
                """
                .replace("%PKG%", pkg)
                .replace("%HUMAN%", s.humanName())
                .replace("%CATEGORY%", s.category())
                .replace("%SEGMENT%", s.targetSegment())
                .replace("%JURIS%", s.jurisdiction())
                .replace("%RATE%", s.baseRate());
    }

    static String productRecord(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.math.BigDecimal;
                import java.util.Objects;
                import java.util.UUID;

                /**
                 * Immutable descriptor for the %HUMAN% product. Values come from the
                 * product catalog and are stamped into the domain layer once the product
                 * is activated for a customer.
                 */
                public record %NAME%Product(
                        UUID productId,
                        String productCode,
                        String displayName,
                        String category,
                        String targetSegment,
                        String jurisdiction,
                        BigDecimal baseRate,
                        BigDecimal minBalance,
                        BigDecimal monthlyFee,
                        BigDecimal feeWaiverBalance,
                        int earlyWithdrawalPenaltyDays,
                        boolean requiresId,
                        int eligibilityAgeMin,
                        int eligibilityAgeMax
                ) {

                    public static %NAME%Product defaults() {
                        return new %NAME%Product(
                                UUID.fromString("00000000-0000-0000-0000-%UUID_SUFFIX%"),
                                "%CODE%",
                                "%HUMAN%",
                                "%CATEGORY%",
                                "%SEGMENT%",
                                "%JURIS%",
                                new BigDecimal("%RATE%"),
                                new BigDecimal("%MIN_BAL%"),
                                new BigDecimal("%FEE%"),
                                new BigDecimal("%WAIVER%"),
                                %PENALTY%,
                                %REQ_ID%,
                                %AGE_MIN%,
                                %AGE_MAX%
                        );
                    }

                    public %NAME%Product {
                        Objects.requireNonNull(productId, "productId");
                        Objects.requireNonNull(productCode, "productCode");
                        Objects.requireNonNull(baseRate, "baseRate");
                        if (baseRate.signum() < 0) {
                            throw new IllegalArgumentException("baseRate cannot be negative");
                        }
                        if (minBalance.signum() < 0) {
                            throw new IllegalArgumentException("minBalance cannot be negative");
                        }
                        if (eligibilityAgeMin < 0 || eligibilityAgeMax < eligibilityAgeMin) {
                            throw new IllegalArgumentException("invalid eligibility age range");
                        }
                    }

                    public boolean isInterestBearing() {
                        return baseRate.signum() > 0;
                    }

                    public boolean isDepositAccount() {
                        return switch (category) {
                            case "CHECKING", "SAVINGS", "MONEY_MARKET", "CERTIFICATE" -> true;
                            default -> false;
                        };
                    }

                    public boolean requiresEarlyWithdrawalPenalty() {
                        return earlyWithdrawalPenaltyDays > 0;
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%CATEGORY%", s.category())
                .replace("%SEGMENT%", s.targetSegment())
                .replace("%JURIS%", s.jurisdiction())
                .replace("%RATE%", s.baseRate())
                .replace("%MIN_BAL%", s.minBalance())
                .replace("%FEE%", s.monthlyFee())
                .replace("%WAIVER%", s.feeWaiverBalance())
                .replace("%PENALTY%", s.earlyWithdrawalPenaltyDays())
                .replace("%REQ_ID%", String.valueOf(s.requiresId()))
                .replace("%AGE_MIN%", String.valueOf(s.eligibilityAgeMin()))
                .replace("%AGE_MAX%", String.valueOf(s.eligibilityAgeMax()))
                .replace("%CODE%", codeOf(s))
                .replace("%UUID_SUFFIX%", uuidSuffix(s));
    }

    static String feeSchedule(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.math.BigDecimal;
                import java.math.RoundingMode;
                import java.time.LocalDate;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Objects;

                /**
                 * Fee schedule for the %HUMAN% product. Captures monthly maintenance,
                 * dormancy, statement-delivery, overdraft, and product-specific
                 * assessments in one canonical place.
                 *
                 * <p>All amounts are USD. Rate basis-points apply where the product
                 * charges tiered rates.
                 */
                public final class %NAME%FeeSchedule {

                    public record FeeLine(String code, String label, BigDecimal amount, String cadence) {
                        public FeeLine {
                            Objects.requireNonNull(code, "code");
                            Objects.requireNonNull(amount, "amount");
                            if (amount.signum() < 0) {
                                throw new IllegalArgumentException("fee amounts must be non-negative");
                            }
                        }
                    }

                    public enum Cadence { MONTHLY, ANNUAL, PER_EVENT, ONE_TIME }

                    private final List<FeeLine> lines;
                    private final Map<String, BigDecimal> waiverThresholdsByCode;
                    private final LocalDate effectiveFrom;
                    private final LocalDate effectiveUntil;

                    public %NAME%FeeSchedule(LocalDate effectiveFrom, LocalDate effectiveUntil) {
                        this.effectiveFrom = effectiveFrom;
                        this.effectiveUntil = effectiveUntil;
                        this.lines = new ArrayList<>();
                        this.waiverThresholdsByCode = new LinkedHashMap<>();
                        seedLines();
                    }

                    public static %NAME%FeeSchedule defaults() {
                        return new %NAME%FeeSchedule(
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2030, 12, 31));
                    }

                    public List<FeeLine> lines() {
                        return Collections.unmodifiableList(lines);
                    }

                    public BigDecimal monthlyMaintenance() {
                        return find("MONTHLY_MAINTENANCE").map(FeeLine::amount).orElse(BigDecimal.ZERO);
                    }

                    public BigDecimal waiverThreshold(String code) {
                        return waiverThresholdsByCode.getOrDefault(code, BigDecimal.ZERO);
                    }

                    public BigDecimal dormancyAssessment(int monthsInactive) {
                        if (monthsInactive < 12) return BigDecimal.ZERO;
                        var base = find("DORMANCY").map(FeeLine::amount).orElse(BigDecimal.ZERO);
                        int overflow = Math.max(0, monthsInactive - 12);
                        return base.add(base.multiply(BigDecimal.valueOf(overflow))
                                        .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_EVEN));
                    }

                    public BigDecimal overdraftItemFee() {
                        return find("OVERDRAFT_ITEM").map(FeeLine::amount).orElse(BigDecimal.ZERO);
                    }

                    public BigDecimal paperStatementFee() {
                        return find("PAPER_STATEMENT").map(FeeLine::amount).orElse(BigDecimal.ZERO);
                    }

                    public BigDecimal annualFee() {
                        return find("ANNUAL").map(FeeLine::amount).orElse(BigDecimal.ZERO);
                    }

                    public BigDecimal earlyWithdrawalPenalty(BigDecimal principal, BigDecimal accruedInterest) {
                        // Flat percentage of interest earned in penalty days, capped at 2% of principal.
                        BigDecimal penalty = accruedInterest.abs();
                        BigDecimal cap = principal.multiply(new BigDecimal("0.02"));
                        return penalty.min(cap);
                    }

                    public boolean isActive(LocalDate asOf) {
                        return !asOf.isBefore(effectiveFrom) && !asOf.isAfter(effectiveUntil);
                    }

                    private java.util.Optional<FeeLine> find(String code) {
                        for (var l : lines) if (l.code().equals(code)) return java.util.Optional.of(l);
                        return java.util.Optional.empty();
                    }

                    private void seedLines() {
                        lines.add(new FeeLine("MONTHLY_MAINTENANCE", "Monthly maintenance",
                                new BigDecimal("%FEE%"), Cadence.MONTHLY.name()));
                        waiverThresholdsByCode.put("MONTHLY_MAINTENANCE", new BigDecimal("%WAIVER%"));

                        lines.add(new FeeLine("PAPER_STATEMENT", "Paper statement delivery",
                                new BigDecimal("2.00"), Cadence.MONTHLY.name()));
                        lines.add(new FeeLine("OVERDRAFT_ITEM", "Overdraft item fee",
                                new BigDecimal("35.00"), Cadence.PER_EVENT.name()));
                        lines.add(new FeeLine("DORMANCY", "Dormant account assessment",
                                new BigDecimal("5.00"), Cadence.MONTHLY.name()));
                        lines.add(new FeeLine("STOP_PAYMENT", "Stop payment order",
                                new BigDecimal("30.00"), Cadence.PER_EVENT.name()));
                        lines.add(new FeeLine("WIRE_DOMESTIC", "Outgoing domestic wire",
                                new BigDecimal("25.00"), Cadence.PER_EVENT.name()));
                        lines.add(new FeeLine("WIRE_INTERNATIONAL", "Outgoing international wire",
                                new BigDecimal("45.00"), Cadence.PER_EVENT.name()));
                        lines.add(new FeeLine("COUNTER_CHECK", "Counter check (per sheet)",
                                new BigDecimal("2.00"), Cadence.PER_EVENT.name()));
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%FEE%", s.monthlyFee())
                .replace("%WAIVER%", s.feeWaiverBalance());
    }

    static String eligibilityRules(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.time.LocalDate;
                import java.time.Period;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Objects;

                /**
                 * Eligibility rule engine for the %HUMAN% product. Evaluates age,
                 * jurisdiction, identification, and segment constraints. Any rule
                 * that fails adds to the returned findings; the caller decides
                 * whether to block, soft-reject, or escalate.
                 */
                public final class %NAME%EligibilityRules {

                    public enum Outcome { ELIGIBLE, NOT_ELIGIBLE, MANUAL_REVIEW }

                    public record Finding(String code, String detail, Severity severity) {
                        public enum Severity { INFO, WARNING, BLOCKER }
                    }

                    public record Assessment(Outcome outcome, List<Finding> findings) {
                        public boolean isEligible() { return outcome == Outcome.ELIGIBLE; }
                    }

                    public record Applicant(
                            String firstName, String lastName,
                            LocalDate dateOfBirth, String homeCountry,
                            String segment, boolean identityVerified,
                            boolean fatcaConfirmed, boolean patriotActCleared
                    ) {}

                    public Assessment evaluate(Applicant applicant, LocalDate asOf) {
                        Objects.requireNonNull(applicant, "applicant");
                        Objects.requireNonNull(asOf, "asOf");

                        List<Finding> findings = new ArrayList<>();
                        int age = Period.between(applicant.dateOfBirth(), asOf).getYears();

                        if (age < %AGE_MIN%) {
                            findings.add(new Finding("AGE_BELOW_MIN",
                                    "Applicant age %d below minimum %AGE_MIN%".formatted(age),
                                    Finding.Severity.BLOCKER));
                        }
                        if (age > %AGE_MAX%) {
                            findings.add(new Finding("AGE_ABOVE_MAX",
                                    "Applicant age %d above maximum %AGE_MAX%".formatted(age),
                                    Finding.Severity.BLOCKER));
                        }

                        if (%REQ_ID%) {
                            if (!applicant.identityVerified()) {
                                findings.add(new Finding("ID_NOT_VERIFIED",
                                        "Identity must be verified before opening %HUMAN%",
                                        Finding.Severity.BLOCKER));
                            }
                            if (!applicant.patriotActCleared()) {
                                findings.add(new Finding("PATRIOT_ACT",
                                        "USA PATRIOT Act OFAC screening not cleared",
                                        Finding.Severity.BLOCKER));
                            }
                        }

                        if (!"%JURIS%".equalsIgnoreCase(applicant.homeCountry())) {
                            findings.add(new Finding("OUT_OF_JURISDICTION",
                                    "Product offered only in %JURIS%; applicant home is "
                                            + applicant.homeCountry(),
                                    Finding.Severity.BLOCKER));
                        }

                        String expectedSegment = "%SEGMENT%";
                        if (!expectedSegment.equalsIgnoreCase(applicant.segment())
                                && !expectedSegment.equalsIgnoreCase("ADULT")) {
                            findings.add(new Finding("SEGMENT_MISMATCH",
                                    "Product segment is " + expectedSegment
                                            + " but applicant is " + applicant.segment(),
                                    Finding.Severity.WARNING));
                        }

                        if (!applicant.fatcaConfirmed() && "%JURIS%".equals("US")) {
                            findings.add(new Finding("FATCA_MISSING",
                                    "FATCA status must be captured for US residents",
                                    Finding.Severity.WARNING));
                        }

                        Outcome outcome = computeOutcome(findings);
                        return new Assessment(outcome, List.copyOf(findings));
                    }

                    private static Outcome computeOutcome(List<Finding> findings) {
                        boolean anyBlocker = findings.stream()
                                .anyMatch(f -> f.severity() == Finding.Severity.BLOCKER);
                        if (anyBlocker) return Outcome.NOT_ELIGIBLE;
                        boolean anyWarning = findings.stream()
                                .anyMatch(f -> f.severity() == Finding.Severity.WARNING);
                        return anyWarning ? Outcome.MANUAL_REVIEW : Outcome.ELIGIBLE;
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%AGE_MIN%", String.valueOf(s.eligibilityAgeMin()))
                .replace("%AGE_MAX%", String.valueOf(s.eligibilityAgeMax()))
                .replace("%REQ_ID%", String.valueOf(s.requiresId()))
                .replace("%JURIS%", s.jurisdiction())
                .replace("%SEGMENT%", s.targetSegment());
    }

    static String disclosure(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.time.LocalDate;
                import java.util.List;

                /**
                 * Regulation-mandated disclosure text for the %HUMAN% product.
                 * In the US, this is the TISA/Truth-in-Savings disclosure content
                 * plus Reg DD required language. Rendered for customer consumption
                 * at account opening and available on-demand thereafter.
                 */
                public final class %NAME%Disclosure {

                    public String title() {
                        return "%HUMAN% — Truth in Savings Disclosure";
                    }

                    public String effectiveDateText(LocalDate effective) {
                        return "This disclosure is effective " + effective + " and supersedes any prior version.";
                    }

                    public List<String> keyTerms() {
                        return List.of(
                                "Annual Percentage Yield (APY): see rate sheet for current APY based on daily balance",
                                "Minimum balance to open the account: $%MIN_BAL%",
                                "Minimum daily balance to avoid fee: see fee schedule",
                                "Monthly maintenance fee: $%FEE% (may be waived based on balance)",
                                "Compounding and crediting: interest compounds daily and credits monthly",
                                "Fees for withdrawals exceeding federal transfer limits may apply"
                        );
                    }

                    public List<String> regulatoryDisclosures() {
                        return List.of(
                                "FDIC insured to the maximum amount allowed by law for this category.",
                                "Member FDIC / Equal Housing Lender.",
                                "Federal law requires all financial institutions to obtain, verify, and record "
                                        + "information that identifies each person who opens an account (USA PATRIOT Act).",
                                "Rates may change at the bank's discretion; customer will be notified of material changes."
                        );
                    }

                    public List<String> earlyWithdrawalPenalty() {
                        if (%PENALTY% <= 0) {
                            return List.of("No early withdrawal penalty applies to the %HUMAN%.");
                        }
                        return List.of(
                                "An early withdrawal penalty applies to the %HUMAN%.",
                                "Penalty equals interest on the amount withdrawn for %PENALTY% days.",
                                "Penalty may reduce principal; partial withdrawals are not permitted "
                                        + "unless specifically authorized in writing."
                        );
                    }

                    public List<String> feeSummary() {
                        return List.of(
                                "Monthly maintenance: $%FEE%",
                                "Waived with qualifying daily balance of $%WAIVER% or more",
                                "Paper statement fee: $2.00/month (waived with e-statements)",
                                "Overdraft item: $35.00 per paid item (max 6 per day)",
                                "Stop payment: $30.00 per request",
                                "Wire transfer (domestic out): $25.00 per transaction",
                                "Wire transfer (international out): $45.00 per transaction"
                        );
                    }

                    public String renderFullDisclosure(LocalDate effective) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(title()).append("\\n");
                        sb.append("=".repeat(title().length())).append("\\n\\n");
                        sb.append(effectiveDateText(effective)).append("\\n\\n");

                        sb.append("KEY TERMS\\n");
                        for (String term : keyTerms()) sb.append(" - ").append(term).append("\\n");
                        sb.append("\\n");

                        sb.append("FEES\\n");
                        for (String fee : feeSummary()) sb.append(" - ").append(fee).append("\\n");
                        sb.append("\\n");

                        sb.append("EARLY WITHDRAWAL\\n");
                        for (String p : earlyWithdrawalPenalty()) sb.append(" - ").append(p).append("\\n");
                        sb.append("\\n");

                        sb.append("REGULATORY DISCLOSURES\\n");
                        for (String d : regulatoryDisclosures()) sb.append(" - ").append(d).append("\\n");
                        return sb.toString();
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%MIN_BAL%", s.minBalance())
                .replace("%FEE%", s.monthlyFee())
                .replace("%WAIVER%", s.feeWaiverBalance())
                .replace("%PENALTY%", s.earlyWithdrawalPenaltyDays());
    }

    static String pricingEngine(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.math.BigDecimal;
                import java.math.MathContext;
                import java.math.RoundingMode;
                import java.time.LocalDate;
                import java.time.temporal.ChronoUnit;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Objects;

                /**
                 * Pricing and interest accrual for the %HUMAN% product. Uses simple
                 * daily accrual at {@link #baseRate()} unless the balance enters a
                 * tiered bucket that triggers a promotional or relationship bonus.
                 *
                 * <p>All arithmetic uses banker's rounding. Negative balances accrue
                 * at the default APR (not APY) — checked downstream by the overdraft
                 * engine.
                 */
                public final class %NAME%PricingEngine {

                    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_EVEN);
                    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

                    public record Tier(BigDecimal lowerInclusive, BigDecimal upperExclusive, BigDecimal bonusBps) {
                        public Tier {
                            Objects.requireNonNull(lowerInclusive, "lowerInclusive");
                            Objects.requireNonNull(bonusBps, "bonusBps");
                        }
                    }

                    public record AccrualResult(
                            BigDecimal dailyAmount,
                            BigDecimal effectiveRate,
                            BigDecimal runningTotal,
                            String tierName,
                            LocalDate accrualDate
                    ) {}

                    private final BigDecimal baseRate;
                    private final List<Tier> tiers;

                    public %NAME%PricingEngine() {
                        this(new BigDecimal("%RATE%"));
                    }

                    public %NAME%PricingEngine(BigDecimal baseRate) {
                        this.baseRate = Objects.requireNonNull(baseRate, "baseRate");
                        this.tiers = defaultTiers();
                    }

                    public BigDecimal baseRate() {
                        return baseRate;
                    }

                    public BigDecimal effectiveRate(BigDecimal balance) {
                        Tier applicable = selectTier(balance);
                        BigDecimal bonus = applicable != null
                                ? applicable.bonusBps().divide(BigDecimal.valueOf(10000), MC)
                                : BigDecimal.ZERO;
                        return baseRate.add(bonus, MC);
                    }

                    public AccrualResult accrueDaily(BigDecimal balance, LocalDate asOf,
                                                     BigDecimal priorTotal) {
                        BigDecimal rate = effectiveRate(balance);
                        BigDecimal daily = balance
                                .multiply(rate, MC)
                                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_EVEN);
                        BigDecimal running = priorTotal.add(daily);
                        Tier applicable = selectTier(balance);
                        String tierName = applicable == null ? "STANDARD"
                                : "TIER_" + applicable.lowerInclusive().toPlainString();
                        return new AccrualResult(daily, rate, running, tierName, asOf);
                    }

                    public BigDecimal annualPercentageYield(BigDecimal balance) {
                        // APY = (1 + r/365)^365 - 1
                        BigDecimal rate = effectiveRate(balance);
                        BigDecimal perDay = rate.divide(DAYS_PER_YEAR, MC);
                        BigDecimal one = BigDecimal.ONE;
                        BigDecimal base = one.add(perDay, MC);
                        BigDecimal compounded = BigDecimal.ONE;
                        // 365 multiplications — accurate enough for disclosure.
                        for (int i = 0; i < 365; i++) {
                            compounded = compounded.multiply(base, MC);
                        }
                        return compounded.subtract(one, MC).setScale(6, RoundingMode.HALF_EVEN);
                    }

                    public BigDecimal projectInterest(BigDecimal principal, LocalDate from, LocalDate to) {
                        long days = ChronoUnit.DAYS.between(from, to);
                        if (days <= 0) return BigDecimal.ZERO;
                        BigDecimal rate = effectiveRate(principal);
                        return principal
                                .multiply(rate, MC)
                                .multiply(BigDecimal.valueOf(days))
                                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_EVEN);
                    }

                    public Tier selectTier(BigDecimal balance) {
                        Tier selected = null;
                        for (Tier t : tiers) {
                            if (balance.compareTo(t.lowerInclusive()) >= 0
                                    && (t.upperExclusive() == null
                                            || balance.compareTo(t.upperExclusive()) < 0)) {
                                selected = t;
                            }
                        }
                        return selected;
                    }

                    private List<Tier> defaultTiers() {
                        List<Tier> list = new ArrayList<>();
                        list.add(new Tier(BigDecimal.ZERO, new BigDecimal("10000"),
                                BigDecimal.ZERO));
                        list.add(new Tier(new BigDecimal("10000"), new BigDecimal("50000"),
                                new BigDecimal("10")));
                        list.add(new Tier(new BigDecimal("50000"), new BigDecimal("250000"),
                                new BigDecimal("20")));
                        list.add(new Tier(new BigDecimal("250000"), null,
                                new BigDecimal("35")));
                        return list;
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%RATE%", s.baseRate());
    }

    static String lifecycleEvent(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.time.Instant;
                import java.util.Objects;
                import java.util.UUID;

                /**
                 * Domain events emitted across the %HUMAN% product lifecycle.
                 * Downstream modules subscribe to these for audit, statements,
                 * notifications, and analytics.
                 */
                public sealed interface %NAME%LifecycleEvent permits
                        %NAME%LifecycleEvent.Opened,
                        %NAME%LifecycleEvent.Funded,
                        %NAME%LifecycleEvent.InterestAccrued,
                        %NAME%LifecycleEvent.FeeAssessed,
                        %NAME%LifecycleEvent.FeeWaived,
                        %NAME%LifecycleEvent.RateChanged,
                        %NAME%LifecycleEvent.Matured,
                        %NAME%LifecycleEvent.Closed {

                    UUID eventId();
                    Instant occurredAt();
                    UUID productId();

                    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                                   String customerReference, String channelId)
                            implements %NAME%LifecycleEvent {
                        public Opened {
                            Objects.requireNonNull(eventId);
                            Objects.requireNonNull(occurredAt);
                        }
                    }

                    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                                   java.math.BigDecimal amount, String source)
                            implements %NAME%LifecycleEvent {}

                    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                                             java.math.BigDecimal dailyAmount,
                                             java.math.BigDecimal runningTotal)
                            implements %NAME%LifecycleEvent {}

                    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                                        String feeCode, java.math.BigDecimal amount)
                            implements %NAME%LifecycleEvent {}

                    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                                      String feeCode, String reason)
                            implements %NAME%LifecycleEvent {}

                    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                                        java.math.BigDecimal previousRate,
                                        java.math.BigDecimal newRate, String reason)
                            implements %NAME%LifecycleEvent {}

                    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                                    String dispositionAction, java.math.BigDecimal finalBalance)
                            implements %NAME%LifecycleEvent {}

                    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                                   String reason, String closedBy)
                            implements %NAME%LifecycleEvent {}
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName());
    }

    static String service(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.math.BigDecimal;
                import java.math.RoundingMode;
                import java.time.Clock;
                import java.time.Instant;
                import java.time.LocalDate;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Objects;
                import java.util.Optional;
                import java.util.UUID;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.function.Consumer;

                /**
                 * Orchestrates the %HUMAN% product lifecycle — opening, funding,
                 * accrual, fee assessment, and closure — backed by an in-memory
                 * store. Production-equivalent wiring would replace the store with
                 * JPA repositories; branching logic stays identical.
                 */
                public final class %NAME%Service {

                    public enum AccountState { PENDING, OPEN, DORMANT, MATURED, CLOSED }

                    public record AccountSnapshot(
                            UUID accountId,
                            String customerReference,
                            AccountState state,
                            BigDecimal balance,
                            BigDecimal accruedInterest,
                            Instant openedAt,
                            Instant lastActivityAt
                    ) {}

                    private final Clock clock;
                    private final %NAME%Product product;
                    private final %NAME%FeeSchedule feeSchedule;
                    private final %NAME%EligibilityRules eligibility;
                    private final %NAME%PricingEngine pricing;
                    private final ConcurrentHashMap<UUID, AccountSnapshot> accounts = new ConcurrentHashMap<>();
                    private final List<Consumer<%NAME%LifecycleEvent>> subscribers = new ArrayList<>();

                    public %NAME%Service(Clock clock) {
                        this.clock = Objects.requireNonNull(clock, "clock");
                        this.product = %NAME%Product.defaults();
                        this.feeSchedule = %NAME%FeeSchedule.defaults();
                        this.eligibility = new %NAME%EligibilityRules();
                        this.pricing = new %NAME%PricingEngine();
                    }

                    public void subscribe(Consumer<%NAME%LifecycleEvent> listener) {
                        subscribers.add(Objects.requireNonNull(listener, "listener"));
                    }

                    public %NAME%Product product() { return product; }
                    public %NAME%FeeSchedule feeSchedule() { return feeSchedule; }
                    public %NAME%EligibilityRules eligibility() { return eligibility; }
                    public %NAME%PricingEngine pricing() { return pricing; }

                    public Optional<AccountSnapshot> find(UUID accountId) {
                        return Optional.ofNullable(accounts.get(accountId));
                    }

                    public AccountSnapshot openAccount(%NAME%EligibilityRules.Applicant applicant,
                                                        String customerReference, String channelId) {
                        var assessment = eligibility.evaluate(applicant, LocalDate.now(clock));
                        if (!assessment.isEligible()) {
                            throw new IllegalStateException("Applicant not eligible: "
                                    + assessment.findings());
                        }

                        UUID accountId = UUID.randomUUID();
                        Instant now = Instant.now(clock);
                        AccountSnapshot snap = new AccountSnapshot(
                                accountId, customerReference,
                                AccountState.PENDING, BigDecimal.ZERO, BigDecimal.ZERO,
                                now, now);
                        accounts.put(accountId, snap);
                        publish(new %NAME%LifecycleEvent.Opened(
                                UUID.randomUUID(), now, product.productId(),
                                customerReference, channelId));
                        return snap;
                    }

                    public AccountSnapshot fund(UUID accountId, BigDecimal amount, String source) {
                        if (amount.signum() <= 0) {
                            throw new IllegalArgumentException("fund amount must be positive");
                        }
                        AccountSnapshot prior = requireAccount(accountId);
                        AccountState nextState = prior.state() == AccountState.PENDING
                                ? AccountState.OPEN : prior.state();
                        Instant now = Instant.now(clock);
                        AccountSnapshot updated = new AccountSnapshot(
                                accountId, prior.customerReference(), nextState,
                                prior.balance().add(amount), prior.accruedInterest(),
                                prior.openedAt(), now);
                        accounts.put(accountId, updated);
                        publish(new %NAME%LifecycleEvent.Funded(
                                UUID.randomUUID(), now, product.productId(), amount, source));
                        return updated;
                    }

                    public AccountSnapshot accrueInterest(UUID accountId, LocalDate asOf) {
                        AccountSnapshot prior = requireAccount(accountId);
                        if (prior.state() != AccountState.OPEN) return prior;
                        var result = pricing.accrueDaily(prior.balance(), asOf, prior.accruedInterest());
                        AccountSnapshot updated = new AccountSnapshot(
                                accountId, prior.customerReference(), prior.state(),
                                prior.balance(), result.runningTotal(),
                                prior.openedAt(), Instant.now(clock));
                        accounts.put(accountId, updated);
                        publish(new %NAME%LifecycleEvent.InterestAccrued(
                                UUID.randomUUID(), Instant.now(clock), product.productId(),
                                result.dailyAmount(), result.runningTotal()));
                        return updated;
                    }

                    public AccountSnapshot assessMaintenanceFee(UUID accountId, LocalDate cycleDate) {
                        AccountSnapshot prior = requireAccount(accountId);
                        if (prior.state() != AccountState.OPEN) return prior;

                        BigDecimal fee = feeSchedule.monthlyMaintenance();
                        if (fee.signum() <= 0) {
                            return prior;
                        }
                        BigDecimal waiver = feeSchedule.waiverThreshold("MONTHLY_MAINTENANCE");
                        if (prior.balance().compareTo(waiver) >= 0 && waiver.signum() > 0) {
                            publish(new %NAME%LifecycleEvent.FeeWaived(
                                    UUID.randomUUID(), Instant.now(clock), product.productId(),
                                    "MONTHLY_MAINTENANCE", "Balance >= waiver threshold"));
                            return prior;
                        }

                        BigDecimal newBalance = prior.balance().subtract(fee)
                                .setScale(2, RoundingMode.HALF_EVEN);
                        AccountSnapshot updated = new AccountSnapshot(
                                accountId, prior.customerReference(), prior.state(),
                                newBalance, prior.accruedInterest(),
                                prior.openedAt(), Instant.now(clock));
                        accounts.put(accountId, updated);
                        publish(new %NAME%LifecycleEvent.FeeAssessed(
                                UUID.randomUUID(), Instant.now(clock), product.productId(),
                                "MONTHLY_MAINTENANCE", fee));
                        return updated;
                    }

                    public AccountSnapshot mature(UUID accountId, String dispositionAction) {
                        AccountSnapshot prior = requireAccount(accountId);
                        if (!product.requiresEarlyWithdrawalPenalty()) {
                            throw new IllegalStateException("Only term products mature");
                        }
                        Instant now = Instant.now(clock);
                        AccountSnapshot updated = new AccountSnapshot(
                                accountId, prior.customerReference(), AccountState.MATURED,
                                prior.balance(), prior.accruedInterest(),
                                prior.openedAt(), now);
                        accounts.put(accountId, updated);
                        publish(new %NAME%LifecycleEvent.Matured(
                                UUID.randomUUID(), now, product.productId(),
                                dispositionAction, prior.balance()));
                        return updated;
                    }

                    public AccountSnapshot close(UUID accountId, String reason, String closedBy) {
                        AccountSnapshot prior = requireAccount(accountId);
                        Instant now = Instant.now(clock);
                        AccountSnapshot updated = new AccountSnapshot(
                                accountId, prior.customerReference(), AccountState.CLOSED,
                                prior.balance(), prior.accruedInterest(),
                                prior.openedAt(), now);
                        accounts.put(accountId, updated);
                        publish(new %NAME%LifecycleEvent.Closed(
                                UUID.randomUUID(), now, product.productId(), reason, closedBy));
                        return updated;
                    }

                    private AccountSnapshot requireAccount(UUID id) {
                        var acct = accounts.get(id);
                        if (acct == null) throw new IllegalArgumentException("Unknown account " + id);
                        return acct;
                    }

                    private void publish(%NAME%LifecycleEvent event) {
                        for (var s : subscribers) {
                            try { s.accept(event); } catch (RuntimeException ignored) {}
                        }
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName());
    }

    static String serviceTest(String pkg, ProductSpec s) {
        return """
                package %PKG%;

                import java.math.BigDecimal;
                import java.time.Clock;
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.ZoneId;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.UUID;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.assertj.core.api.Assertions.assertThatThrownBy;

                class %NAME%ServiceTest {

                    private Clock clock;
                    private %NAME%Service service;
                    private List<%NAME%LifecycleEvent> events;

                    @BeforeEach
                    void setUp() {
                        clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"));
                        service = new %NAME%Service(clock);
                        events = new ArrayList<>();
                        service.subscribe(events::add);
                    }

                    @Test
                    void product_defaults_load_expected_values() {
                        var p = %NAME%Product.defaults();
                        assertThat(p.displayName()).isEqualTo("%HUMAN%");
                        assertThat(p.category()).isEqualTo("%CATEGORY%");
                        assertThat(p.jurisdiction()).isEqualTo("%JURIS%");
                    }

                    @Test
                    void pricing_engine_rate_is_at_least_base_rate() {
                        var engine = new %NAME%PricingEngine();
                        var eff = engine.effectiveRate(new BigDecimal("1000"));
                        assertThat(eff).isGreaterThanOrEqualTo(engine.baseRate());
                    }

                    @Test
                    void high_balance_tier_bonus_applied() {
                        var engine = new %NAME%PricingEngine();
                        var low = engine.effectiveRate(new BigDecimal("100"));
                        var high = engine.effectiveRate(new BigDecimal("500000"));
                        assertThat(high).isGreaterThanOrEqualTo(low);
                    }

                    @Test
                    void fee_schedule_monthly_maintenance_matches_spec() {
                        var fs = %NAME%FeeSchedule.defaults();
                        assertThat(fs.monthlyMaintenance())
                                .isEqualByComparingTo(new BigDecimal("%FEE%"));
                    }

                    @Test
                    void fee_schedule_is_active_within_effective_window() {
                        var fs = %NAME%FeeSchedule.defaults();
                        assertThat(fs.isActive(LocalDate.of(2027, 1, 1))).isTrue();
                        assertThat(fs.isActive(LocalDate.of(2020, 1, 1))).isFalse();
                    }

                    @Test
                    void dormancy_fee_zero_before_twelve_months() {
                        var fs = %NAME%FeeSchedule.defaults();
                        assertThat(fs.dormancyAssessment(11))
                                .isEqualByComparingTo(BigDecimal.ZERO);
                    }

                    @Test
                    void eligibility_blocks_age_under_minimum() {
                        var rules = new %NAME%EligibilityRules();
                        var dob = LocalDate.of(2026, 6, 1).minusYears(Math.max(0, %AGE_MIN% - 1));
                        var applicant = applicant(dob);
                        var result = rules.evaluate(applicant, LocalDate.of(2026, 6, 1));
                        if (%AGE_MIN% > 0) {
                            assertThat(result.outcome())
                                    .isEqualTo(%NAME%EligibilityRules.Outcome.NOT_ELIGIBLE);
                        } else {
                            assertThat(result).isNotNull();
                        }
                    }

                    @Test
                    void eligibility_passes_for_adult_applicant_in_jurisdiction() {
                        var rules = new %NAME%EligibilityRules();
                        int targetAge = Math.max(%AGE_MIN%, Math.min(%AGE_MIN% + 5, %AGE_MAX%));
                        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
                        var applicant = applicant(dob);
                        var result = rules.evaluate(applicant, LocalDate.of(2026, 6, 1));
                        assertThat(result.outcome())
                                .isIn(%NAME%EligibilityRules.Outcome.ELIGIBLE,
                                      %NAME%EligibilityRules.Outcome.MANUAL_REVIEW);
                    }

                    @Test
                    void open_and_fund_round_trip() {
                        int targetAge = Math.max(%AGE_MIN%, Math.min(%AGE_MIN% + 5, %AGE_MAX%));
                        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
                        var applicant = applicant(dob);
                        var snap = service.openAccount(applicant, "cust-001", "WEB");
                        var funded = service.fund(snap.accountId(),
                                new BigDecimal("2500.00"), "ACH-INITIAL");
                        assertThat(funded.state()).isEqualTo(%NAME%Service.AccountState.OPEN);
                        assertThat(funded.balance()).isEqualByComparingTo("2500.00");
                        assertThat(events).isNotEmpty();
                    }

                    @Test
                    void fund_negative_amount_rejected() {
                        int targetAge = Math.max(%AGE_MIN%, Math.min(%AGE_MIN% + 5, %AGE_MAX%));
                        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
                        var snap = service.openAccount(applicant(dob), "cust-002", "WEB");
                        assertThatThrownBy(() -> service.fund(snap.accountId(),
                                new BigDecimal("-10"), "bad"))
                                .isInstanceOf(IllegalArgumentException.class);
                    }

                    @Test
                    void accrue_interest_updates_running_total() {
                        int targetAge = Math.max(%AGE_MIN%, Math.min(%AGE_MIN% + 5, %AGE_MAX%));
                        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
                        var snap = service.openAccount(applicant(dob), "cust-003", "WEB");
                        var funded = service.fund(snap.accountId(),
                                new BigDecimal("10000"), "ACH");
                        var accrued = service.accrueInterest(funded.accountId(),
                                LocalDate.of(2026, 6, 2));
                        assertThat(accrued.accruedInterest())
                                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
                    }

                    @Test
                    void disclosure_renders_full_text() {
                        var d = new %NAME%Disclosure();
                        var text = d.renderFullDisclosure(LocalDate.of(2026, 6, 1));
                        assertThat(text).contains("%HUMAN%").contains("REGULATORY");
                    }

                    private %NAME%EligibilityRules.Applicant applicant(LocalDate dob) {
                        return new %NAME%EligibilityRules.Applicant(
                                "Alex", "Demo", dob,
                                "%JURIS%",
                                "%SEGMENT%",
                                true, true, true);
                    }
                }
                """
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%CATEGORY%", s.category())
                .replace("%JURIS%", s.jurisdiction())
                .replace("%FEE%", s.monthlyFee())
                .replace("%AGE_MIN%", String.valueOf(s.eligibilityAgeMin()))
                .replace("%AGE_MAX%", String.valueOf(s.eligibilityAgeMax()))
                .replace("%SEGMENT%", s.targetSegment());
    }

    private static String codeOf(ProductSpec s) {
        return s.category().substring(0, 3) + "-" + s.name().toUpperCase();
    }

    private static String uuidSuffix(ProductSpec s) {
        String h = String.format("%08x", Math.abs(s.name().hashCode()));
        return h.substring(0, Math.min(12, h.length()))
                + "0".repeat(Math.max(0, 12 - h.length()));
    }
}
