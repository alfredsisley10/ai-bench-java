package com.omnibank.risk.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Value-at-Risk (VaR) and Expected Shortfall (Conditional VaR / CVaR) engine.
 *
 * <p>Supports the two workhorse methodologies a mid-sized bank uses for FRTB
 * standardised-approach market-risk reporting:
 *
 * <ul>
 *   <li><b>Historical simulation</b> — apply the empirical distribution of
 *       price returns observed over a look-back window to today's
 *       mark-to-market value.</li>
 *   <li><b>Parametric (variance-covariance)</b> — assume returns are Normal
 *       and scale portfolio volatility by the z-score for the target
 *       confidence.</li>
 * </ul>
 *
 * <p>Both 95% and 99% one-day confidence levels are produced. The engine also
 * computes <b>Expected Shortfall</b>, the mean loss conditional on breaching
 * the VaR threshold — this is the Basel III measure that replaces VaR under
 * FRTB. Finally, <b>Stressed VaR</b> re-runs the historical calculation using
 * a user-supplied stress window (e.g. 2008-2009) to satisfy Basel 2.5.
 *
 * <p>All returns are arithmetic returns; multi-day horizons are scaled by
 * √t (square-root-of-time rule) which assumes IID returns — fine for
 * overnight reporting, not for quarterly horizons.
 */
public class ValueAtRiskCalculator {

    private static final Logger log = LoggerFactory.getLogger(ValueAtRiskCalculator.class);

    /** Confidence levels we always report. */
    public enum Confidence {
        C95(0.95, 1.6448536269514722),  // standard-normal z at 95%
        C99(0.99, 2.3263478740408408);

        private final double level;
        private final double zScore;

        Confidence(double level, double zScore) {
            this.level = level;
            this.zScore = zScore;
        }
        public double level() { return level; }
        public double zScore() { return zScore; }
    }

    /** A single trading book / portfolio position. */
    public record Position(
            String positionId,
            String instrumentId,
            BigDecimal quantity,
            Money markToMarket
    ) {
        public Position {
            Objects.requireNonNull(positionId, "positionId");
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(markToMarket, "markToMarket");
        }
    }

    /** Historical return series for an instrument: one return per trading day. */
    public record ReturnSeries(String instrumentId, List<BigDecimal> dailyReturns) {
        public ReturnSeries {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(dailyReturns, "dailyReturns");
            dailyReturns = List.copyOf(dailyReturns);
        }
    }

    /** Composite portfolio snapshot to feed VaR. */
    public record Portfolio(String portfolioId, List<Position> positions, CurrencyCode baseCurrency) {
        public Portfolio {
            Objects.requireNonNull(portfolioId, "portfolioId");
            Objects.requireNonNull(baseCurrency, "baseCurrency");
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    /** Output row per confidence level. */
    public record VarResult(
            String portfolioId,
            Methodology methodology,
            Confidence confidence,
            int horizonDays,
            Money varAmount,
            Money expectedShortfall,
            Instant computedAt
    ) {}

    public enum Methodology { HISTORICAL, PARAMETRIC, STRESSED }

    /** Event emitted when a VaR limit has been exceeded. */
    public record VarLimitBreachEvent(
            UUID eventId, Instant occurredAt,
            String portfolioId, Confidence confidence,
            Money varAmount, Money limit)
            implements DomainEvent {
        @Override public String eventType() { return "risk.var.limit_breach"; }
    }

    private final Clock clock;
    private final EventBus events;

    public ValueAtRiskCalculator(Clock clock, EventBus events) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Historical simulation VaR. Builds the portfolio P/L vector by applying
     * the return series of each instrument to the current MTM, then picks
     * the loss at the (1 - confidence) quantile of that empirical distribution.
     */
    public VarResult historicalVar(Portfolio portfolio,
                                   Map<String, ReturnSeries> returnsByInstrument,
                                   Confidence confidence,
                                   int horizonDays) {
        validateHorizon(horizonDays);
        if (portfolio.positions().isEmpty()) {
            return zeroResult(portfolio, Methodology.HISTORICAL, confidence, horizonDays);
        }

        double[] portfolioPnlOneDay = aggregatePortfolioReturns(portfolio, returnsByInstrument);
        if (portfolioPnlOneDay.length == 0) {
            return zeroResult(portfolio, Methodology.HISTORICAL, confidence, horizonDays);
        }

        double[] losses = negate(portfolioPnlOneDay);       // positive numbers = losses
        double scale = Math.sqrt(horizonDays);              // √t scaling for multi-day
        for (int i = 0; i < losses.length; i++) losses[i] *= scale;

        double varDollars = percentile(losses, confidence.level());
        double esDollars = tailMean(losses, confidence.level());

        VarResult result = buildResult(portfolio, Methodology.HISTORICAL,
                confidence, horizonDays, varDollars, esDollars);
        log.debug("Historical VaR {}: {} ES: {}", confidence, result.varAmount(), result.expectedShortfall());
        return result;
    }

    /**
     * Parametric (variance-covariance) VaR. Computes portfolio volatility from
     * the return series (assuming zero mean, a common overnight assumption)
     * and scales by the confidence z-score and √t.
     */
    public VarResult parametricVar(Portfolio portfolio,
                                   Map<String, ReturnSeries> returnsByInstrument,
                                   Confidence confidence,
                                   int horizonDays) {
        validateHorizon(horizonDays);
        if (portfolio.positions().isEmpty()) {
            return zeroResult(portfolio, Methodology.PARAMETRIC, confidence, horizonDays);
        }

        double[] pnl = aggregatePortfolioReturns(portfolio, returnsByInstrument);
        if (pnl.length == 0) {
            return zeroResult(portfolio, Methodology.PARAMETRIC, confidence, horizonDays);
        }
        double mean = mean(pnl);
        double stdev = stdev(pnl, mean);
        double scale = Math.sqrt(horizonDays);
        double varDollars = confidence.zScore() * stdev * scale;

        // For a Normal distribution, ES = σ · φ(z) / (1 - α)
        double phi = normalPdf(confidence.zScore());
        double esDollars = stdev * phi / (1 - confidence.level()) * scale;

        VarResult result = buildResult(portfolio, Methodology.PARAMETRIC,
                confidence, horizonDays, varDollars, esDollars);
        log.debug("Parametric VaR {}: {} ES: {}", confidence, result.varAmount(), result.expectedShortfall());
        return result;
    }

    /**
     * Stressed VaR — historical simulation using a user-supplied crisis window
     * of return series (e.g. Aug 2008 – Mar 2009) instead of the current
     * observation period.
     */
    public VarResult stressedVar(Portfolio portfolio,
                                 Map<String, ReturnSeries> stressSeries,
                                 Confidence confidence,
                                 int horizonDays) {
        VarResult historical = historicalVar(portfolio, stressSeries, confidence, horizonDays);
        return new VarResult(historical.portfolioId(), Methodology.STRESSED,
                historical.confidence(), historical.horizonDays(),
                historical.varAmount(), historical.expectedShortfall(),
                historical.computedAt());
    }

    /**
     * Convenience: compute VaR at both 95% and 99% for a single methodology.
     */
    public List<VarResult> fullSuite(Portfolio portfolio,
                                     Map<String, ReturnSeries> returnsByInstrument,
                                     Methodology methodology,
                                     int horizonDays) {
        List<VarResult> out = new ArrayList<>();
        for (Confidence c : Confidence.values()) {
            out.add(switch (methodology) {
                case HISTORICAL -> historicalVar(portfolio, returnsByInstrument, c, horizonDays);
                case PARAMETRIC -> parametricVar(portfolio, returnsByInstrument, c, horizonDays);
                case STRESSED -> stressedVar(portfolio, returnsByInstrument, c, horizonDays);
            });
        }
        return out;
    }

    /**
     * Emit a domain event if the computed VaR breaches a supplied limit.
     */
    public Optional<VarLimitBreachEvent> checkLimit(VarResult result, Money limit) {
        if (result.varAmount().compareTo(limit) > 0) {
            VarLimitBreachEvent event = new VarLimitBreachEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    result.portfolioId(), result.confidence(),
                    result.varAmount(), limit);
            events.publish(event);
            log.warn("VaR limit breached: portfolio={} var={} limit={}",
                    result.portfolioId(), result.varAmount(), limit);
            return Optional.of(event);
        }
        return Optional.empty();
    }

    /* ---------- Pure-math helpers (package-private for tests) ---------- */

    /**
     * For each observation index i, sum across all positions the dollar P/L:
     * quantity × MTMprice × return_i(instrument).
     * Returns an array whose length is the minimum number of observations across
     * all participating instruments.
     */
    double[] aggregatePortfolioReturns(Portfolio portfolio,
                                       Map<String, ReturnSeries> returnsByInstrument) {
        int minObs = Integer.MAX_VALUE;
        for (Position p : portfolio.positions()) {
            ReturnSeries s = returnsByInstrument.get(p.instrumentId());
            if (s == null || s.dailyReturns().isEmpty()) {
                minObs = Math.min(minObs, 0);
            } else {
                minObs = Math.min(minObs, s.dailyReturns().size());
            }
        }
        if (minObs == Integer.MAX_VALUE || minObs == 0) {
            return new double[0];
        }

        double[] pnl = new double[minObs];
        for (Position p : portfolio.positions()) {
            ReturnSeries s = returnsByInstrument.get(p.instrumentId());
            double exposure = p.markToMarket().amount().doubleValue();
            // If quantity is signed (short positions) it's already reflected in MTM,
            // but we carry sign explicitly in case MTM is gross.
            double qtySign = Math.signum(p.quantity().doubleValue());
            if (qtySign == 0) qtySign = 1;
            for (int i = 0; i < minObs; i++) {
                pnl[i] += qtySign * exposure * s.dailyReturns().get(i).doubleValue();
            }
        }
        return pnl;
    }

    /** The loss at the given confidence percentile. */
    static double percentile(double[] losses, double confidence) {
        if (losses.length == 0) return 0.0;
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        double rank = confidence * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) return sorted[lower];
        double weight = rank - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    /** Mean of losses beyond the VaR cutoff — Expected Shortfall / CVaR. */
    static double tailMean(double[] losses, double confidence) {
        if (losses.length == 0) return 0.0;
        double cutoff = percentile(losses, confidence);
        double sum = 0.0;
        int count = 0;
        for (double l : losses) {
            if (l >= cutoff) {
                sum += l;
                count++;
            }
        }
        return count == 0 ? cutoff : sum / count;
    }

    static double mean(double[] xs) {
        double s = 0.0;
        for (double x : xs) s += x;
        return xs.length == 0 ? 0.0 : s / xs.length;
    }

    static double stdev(double[] xs, double mean) {
        if (xs.length < 2) return 0.0;
        double s = 0.0;
        for (double x : xs) {
            double d = x - mean;
            s += d * d;
        }
        // Sample standard deviation (unbiased)
        return Math.sqrt(s / (xs.length - 1));
    }

    /** Standard-Normal PDF evaluated at z. */
    static double normalPdf(double z) {
        return Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
    }

    private static double[] negate(double[] xs) {
        double[] out = new double[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = -xs[i];
        return out;
    }

    private VarResult buildResult(Portfolio portfolio, Methodology methodology,
                                   Confidence confidence, int horizonDays,
                                   double varDollars, double esDollars) {
        varDollars = Math.max(0.0, varDollars);
        esDollars = Math.max(varDollars, esDollars);
        Money var = toMoney(varDollars, portfolio.baseCurrency());
        Money es = toMoney(esDollars, portfolio.baseCurrency());
        return new VarResult(portfolio.portfolioId(), methodology,
                confidence, horizonDays, var, es, Timestamp.now(clock));
    }

    private VarResult zeroResult(Portfolio portfolio, Methodology methodology,
                                  Confidence confidence, int horizonDays) {
        Money zero = Money.zero(portfolio.baseCurrency());
        return new VarResult(portfolio.portfolioId(), methodology,
                confidence, horizonDays, zero, zero, Timestamp.now(clock));
    }

    private static Money toMoney(double dollars, CurrencyCode currency) {
        BigDecimal bd = BigDecimal.valueOf(dollars)
                .setScale(currency.minorUnits(), RoundingMode.HALF_EVEN);
        return Money.of(bd, currency);
    }

    private static void validateHorizon(int horizonDays) {
        if (horizonDays < 1 || horizonDays > 250) {
            throw new IllegalArgumentException(
                    "horizonDays must be between 1 and 250, got " + horizonDays);
        }
    }

    /** Debug pretty-printer — handy when reconciling against a spreadsheet. */
    public static String format(VarResult r) {
        return "%s[%s %.0f%% %dd]: VaR=%s ES=%s".formatted(
                r.methodology(), r.portfolioId(),
                r.confidence().level() * 100, r.horizonDays(),
                r.varAmount(), r.expectedShortfall());
    }

    /** Small diagnostic wrapper, primarily for logs. */
    static String formatDouble(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /** Produce an empty portfolio marker (useful from tests). */
    public static Portfolio emptyPortfolio(String id, CurrencyCode ccy) {
        return new Portfolio(id, Collections.emptyList(), ccy);
    }
}
