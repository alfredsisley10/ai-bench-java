package com.omnibank.risk.internal;

import com.omnibank.risk.api.CreditScore;
import com.omnibank.risk.internal.CreditScoringModel.CreditProfile;
import com.omnibank.risk.internal.CreditScoringModel.Factor;
import com.omnibank.risk.internal.CreditScoringModel.HardInquiry;
import com.omnibank.risk.internal.CreditScoringModel.PublicRecord;
import com.omnibank.risk.internal.CreditScoringModel.PublicRecordType;
import com.omnibank.risk.internal.CreditScoringModel.ScoreBand;
import com.omnibank.risk.internal.CreditScoringModel.ScoringResult;
import com.omnibank.risk.internal.CreditScoringModel.Tradeline;
import com.omnibank.risk.internal.CreditScoringModel.TradelineType;
import com.omnibank.shared.domain.CustomerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditScoringModelTest {

    private static final CustomerId CUST = CustomerId.newId();
    private static final LocalDate AS_OF = LocalDate.parse("2026-04-16");

    private RecordingEventBus events;
    private CreditScoringModel model;

    @BeforeEach
    void setUp() {
        events = new RecordingEventBus();
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        model = new CreditScoringModel(clock, events);
    }

    @Test
    void empty_profile_receives_thin_file_minimum_score() {
        CreditProfile profile = new CreditProfile(CUST, List.of(), List.of(), List.of(), AS_OF);
        ScoringResult r = model.score(profile);

        // Thin-file profiles land well below the 670 GOOD threshold — the exact
        // number depends on factor defaults, so we just assert "poor or fair".
        assertThat(r.score().fico()).isLessThan(600);
        assertThat(r.topAdverseReasons())
                .anyMatch(s -> s.toLowerCase().contains("insufficient")
                        || s.toLowerCase().contains("no credit")
                        || s.toLowerCase().contains("diversity"));
    }

    @Test
    void excellent_profile_produces_high_fico_score() {
        Tradeline revolving = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2008-01-01"), Optional.empty(),
                new BigDecimal("20000"), new BigDecimal("500"),
                0, 0, 0, false, 24, 24);
        Tradeline installment = new Tradeline("T2", TradelineType.INSTALLMENT_AUTO,
                LocalDate.parse("2012-06-01"), Optional.empty(),
                new BigDecimal("35000"), new BigDecimal("12000"),
                0, 0, 0, false, 24, 24);
        Tradeline mortgage = new Tradeline("T3", TradelineType.MORTGAGE,
                LocalDate.parse("2005-04-01"), Optional.empty(),
                new BigDecimal("400000"), new BigDecimal("180000"),
                0, 0, 0, false, 24, 24);

        CreditProfile profile = new CreditProfile(CUST,
                List.of(revolving, installment, mortgage),
                List.of(), List.of(), AS_OF);

        ScoringResult r = model.score(profile);
        assertThat(r.score().fico()).isGreaterThanOrEqualTo(780);
        assertThat(ScoreBand.forScore(r.score().fico()))
                .isIn(ScoreBand.VERY_GOOD, ScoreBand.EXCELLENT);
    }

    @Test
    void delinquencies_and_charge_off_depress_score_meaningfully() {
        Tradeline bad = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2018-01-01"), Optional.empty(),
                new BigDecimal("10000"), new BigDecimal("9800"),
                3, 2, 2, true, 18, 24);

        CreditProfile profile = new CreditProfile(CUST, List.of(bad),
                List.of(), List.of(), AS_OF);

        ScoringResult r = model.score(profile);
        assertThat(r.score().fico()).isLessThan(600);
        assertThat(r.topAdverseReasons())
                .anyMatch(s -> s.toLowerCase().contains("delinquency")
                        || s.toLowerCase().contains("charge-off"));
    }

    @Test
    void high_utilisation_produces_low_utilisation_factor_score() {
        Tradeline revolver = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2020-01-01"), Optional.empty(),
                new BigDecimal("10000"), new BigDecimal("9500"),
                0, 0, 0, false, 24, 24);

        CreditProfile profile = new CreditProfile(CUST, List.of(revolver),
                List.of(), List.of(), AS_OF);
        ScoringResult r = model.score(profile);

        var utilContribution = r.contributions().stream()
                .filter(c -> c.factor() == Factor.UTILIZATION)
                .findFirst().orElseThrow();
        assertThat(utilContribution.rawScore()).isLessThan(new BigDecimal("0.50"));
    }

    @Test
    void factor_weights_sum_to_one() {
        BigDecimal sum = CreditScoringModel.WEIGHTS.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void bankruptcy_hits_payment_history_hard() {
        Tradeline line = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2015-01-01"), Optional.empty(),
                new BigDecimal("5000"), new BigDecimal("800"),
                0, 0, 0, false, 24, 24);

        CreditProfile profile = new CreditProfile(CUST, List.of(line),
                List.of(),
                List.of(new PublicRecord(LocalDate.parse("2024-01-15"), PublicRecordType.BANKRUPTCY_CH7)),
                AS_OF);

        ScoringResult r = model.score(profile);
        assertThat(r.topAdverseReasons())
                .anyMatch(s -> s.toLowerCase().contains("public record"));
    }

    @Test
    void many_recent_inquiries_penalise_new_credit_factor() {
        Tradeline line = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2018-01-01"), Optional.empty(),
                new BigDecimal("5000"), new BigDecimal("500"),
                0, 0, 0, false, 24, 24);

        List<HardInquiry> inquiries = List.of(
                new HardInquiry(AS_OF.minusMonths(1), "src-1"),
                new HardInquiry(AS_OF.minusMonths(2), "src-2"),
                new HardInquiry(AS_OF.minusMonths(3), "src-3"),
                new HardInquiry(AS_OF.minusMonths(4), "src-4"),
                new HardInquiry(AS_OF.minusMonths(5), "src-5"));

        CreditProfile profile = new CreditProfile(CUST, List.of(line), inquiries, List.of(), AS_OF);
        ScoringResult r = model.score(profile);

        var newCredit = r.contributions().stream()
                .filter(c -> c.factor() == Factor.NEW_CREDIT)
                .findFirst().orElseThrow();
        assertThat(newCredit.rawScore()).isLessThan(new BigDecimal("0.70"));
        assertThat(r.topAdverseReasons())
                .anyMatch(s -> s.toLowerCase().contains("inquir"));
    }

    @Test
    void scoring_publishes_computed_event() {
        Tradeline line = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2015-01-01"), Optional.empty(),
                new BigDecimal("5000"), new BigDecimal("500"),
                0, 0, 0, false, 24, 24);

        CreditProfile profile = new CreditProfile(CUST, List.of(line),
                List.of(), List.of(), AS_OF);
        model.score(profile);

        assertThat(events.events).anyMatch(e -> e instanceof CreditScoringModel.CreditScoreComputedEvent);
    }

    @Test
    void score_is_always_in_regulatory_range() {
        Tradeline bad = new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2023-01-01"), Optional.empty(),
                new BigDecimal("1000"), new BigDecimal("990"),
                10, 10, 10, true, 0, 24);

        CreditProfile profile = new CreditProfile(CUST, List.of(bad),
                List.of(), List.of(
                        new PublicRecord(LocalDate.parse("2025-01-01"), PublicRecordType.BANKRUPTCY_CH7),
                        new PublicRecord(LocalDate.parse("2025-06-01"), PublicRecordType.FORECLOSURE)
                ), AS_OF);

        ScoringResult r = model.score(profile);
        assertThat(r.score().fico()).isBetween(CreditScoringModel.MIN_SCORE, CreditScoringModel.MAX_SCORE);
    }

    @Test
    void invalid_tradeline_payment_counts_throw() {
        assertThatThrownBy(() -> new Tradeline("T1", TradelineType.REVOLVING_CARD,
                LocalDate.parse("2020-01-01"), Optional.empty(),
                new BigDecimal("5000"), new BigDecimal("500"),
                0, 0, 0, false, 30, 24))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grade_mapping_follows_score_thresholds() {
        assertThat(CreditScoringModel.gradeFor(830)).isEqualTo(CreditScore.Grade.AAA);
        assertThat(CreditScoringModel.gradeFor(780)).isEqualTo(CreditScore.Grade.AA);
        assertThat(CreditScoringModel.gradeFor(740)).isEqualTo(CreditScore.Grade.A);
        assertThat(CreditScoringModel.gradeFor(700)).isEqualTo(CreditScore.Grade.BBB);
        assertThat(CreditScoringModel.gradeFor(660)).isEqualTo(CreditScore.Grade.BB);
        assertThat(CreditScoringModel.gradeFor(620)).isEqualTo(CreditScore.Grade.B);
        assertThat(CreditScoringModel.gradeFor(580)).isEqualTo(CreditScore.Grade.CCC);
        assertThat(CreditScoringModel.gradeFor(540)).isEqualTo(CreditScore.Grade.CC);
        assertThat(CreditScoringModel.gradeFor(500)).isEqualTo(CreditScore.Grade.C);
        assertThat(CreditScoringModel.gradeFor(350)).isEqualTo(CreditScore.Grade.D);
    }
}
