package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.HmdaReportGenerator.ActionTaken;
import com.omnibank.regreporting.internal.HmdaReportGenerator.DenialReason;
import com.omnibank.regreporting.internal.HmdaReportGenerator.EditCategory;
import com.omnibank.regreporting.internal.HmdaReportGenerator.FilerProfile;
import com.omnibank.regreporting.internal.HmdaReportGenerator.HmdaLarFile;
import com.omnibank.regreporting.internal.HmdaReportGenerator.LoanApplication;
import com.omnibank.regreporting.internal.HmdaReportGenerator.LoanPurpose;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmdaReportGeneratorTest {

    private static Money usd(String v) { return Money.of(v, CurrencyCode.USD); }

    private HmdaReportGenerator gen;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"), ZoneId.of("UTC"));
        gen = new HmdaReportGenerator(clock);
    }

    private LoanApplication valid() {
        return HmdaReportGenerator.stub(
                "OMN-2026-00001-LOAN-000000000001",
                LocalDate.of(2026, 1, 15),
                CustomerId.newId(),
                LoanPurpose.HOME_PURCHASE, ActionTaken.ORIGINATED,
                usd("350000"), "NY", "001", "36001002101");
    }

    @Test
    void valid_record_produces_no_edits() {
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank NA", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(valid()));
        assertThat(file.edits()).isEmpty();
        assertThat(file.hasBlockingEdits()).isFalse();
    }

    @Test
    void denied_application_without_denial_reason_triggers_edit() {
        LoanApplication denied = new LoanApplication(
                "OMN-2026-00002-LOAN-000000000002",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.HOME_PURCHASE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.DENIED, DenialReason.NONE,
                "NY", "001", "36001002101",
                new BigDecimal("0.065"),
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("0.80"), new BigDecimal("0.35"), 360,
                false, false, false);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank NA", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(denied));
        assertThat(file.edits()).anyMatch(e -> e.editCode().equals("V612"));
    }

    @Test
    void short_ulid_triggers_s300() {
        LoanApplication shortUlid = new LoanApplication(
                "TOO-SHORT", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.HOME_PURCHASE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.ORIGINATED, DenialReason.NONE,
                "NY", "001", "36001002101",
                new BigDecimal("0.065"),
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("0.80"), new BigDecimal("0.35"), 360,
                false, false, false);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(shortUlid));
        assertThat(file.edits()).anyMatch(e -> e.editCode().equals("S300"));
    }

    @Test
    void action_date_before_application_date_triggers_v601() {
        LoanApplication weird = new LoanApplication(
                "OMN-2026-00005-LOAN-000000000005",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 15),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.HOME_PURCHASE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.ORIGINATED, DenialReason.NONE,
                "NY", "001", "36001002101",
                new BigDecimal("0.065"),
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("0.80"), new BigDecimal("0.35"), 360,
                false, false, false);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(weird));
        assertThat(file.edits()).anyMatch(e -> e.editCode().equals("V601"));
    }

    @Test
    void invalid_state_code_triggers_v700() {
        LoanApplication bad = new LoanApplication(
                "OMN-2026-00006-LOAN-000000000006",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.HOME_PURCHASE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.ORIGINATED, DenialReason.NONE,
                "xx", "001", "36001002101",
                new BigDecimal("0.065"),
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("0.80"), new BigDecimal("0.35"), 360,
                false, false, false);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(bad));
        assertThat(file.edits()).anyMatch(e -> e.editCode().equals("V700"));
    }

    @Test
    void quality_edits_fire_on_extreme_values() {
        LoanApplication extreme = new LoanApplication(
                "OMN-2026-00007-LOAN-000000000007",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.HOME_PURCHASE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.ORIGINATED, DenialReason.NONE,
                "NY", "001", "36001002101",
                new BigDecimal("0.30"),     // 30% rate — Q650
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("1.50"),     // 150% CLTV — Q640
                new BigDecimal("0.80"),     // 80% DTI — Q635
                360, false, false, false);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(extreme));
        assertThat(file.edits()).extracting(e -> e.editCode())
                .contains("Q635", "Q640", "Q650");
    }

    @Test
    void business_loan_for_non_residential_purpose_is_excluded() {
        LoanApplication bus = new LoanApplication(
                "OMN-2026-00008-LOAN-000000000008",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.OTHER_PURPOSE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.ORIGINATED, DenialReason.NONE,
                "NY", "001", "36001002101",
                new BigDecimal("0.065"),
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("0.80"), new BigDecimal("0.35"), 360,
                false, false, true);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(bus));
        assertThat(file.coveredRecords()).isEmpty();
    }

    @Test
    void duplicate_ulid_is_detected() {
        LoanApplication a = valid();
        LoanApplication b = valid();
        assertThat(HmdaReportGenerator.duplicateUlids(List.of(a, b)))
                .containsExactly(a.universalLoanId());
    }

    @Test
    void lei_format_must_be_20_uppercase_alphanumerics() {
        assertThatThrownBy(() -> HmdaReportGenerator.requireValidLei("short"))
                .isInstanceOf(IllegalArgumentException.class);
        HmdaReportGenerator.requireValidLei("ABCDEFGHIJKLMNOPQRST");
    }

    @Test
    void lar_file_body_contains_transmittal_and_record_lines() {
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(valid()));
        assertThat(file.fileBody()).startsWith("1|ABCDEFGHIJKLMNOPQRST|2026|Omnibank|1|");
        assertThat(file.fileBody()).contains("\n2|");
    }

    @Test
    void past_due_after_march_1() {
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, List.of(valid()));
        // Clock is Feb 15 2026, LAR due March 1, 2027 — not past due.
        assertThat(gen.isPastDue(file)).isFalse();
    }

    @Test
    void edit_counts_breaks_out_by_category() {
        List<LoanApplication> list = new ArrayList<>();
        // One record with multiple Q edits
        LoanApplication extreme = new LoanApplication(
                "OMN-2026-00009-LOAN-000000000009",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20),
                CustomerId.newId(), HmdaReportGenerator.LoanType.CONVENTIONAL,
                LoanPurpose.HOME_PURCHASE,
                HmdaReportGenerator.ConstructionMethod.SITE_BUILT,
                HmdaReportGenerator.OccupancyType.PRINCIPAL,
                usd("300000"), usd("600000"), usd("100000"),
                ActionTaken.ORIGINATED, DenialReason.NONE,
                "NY", "001", "36001002101",
                new BigDecimal("0.30"),
                HmdaReportGenerator.ApplicantSex.NOT_PROVIDED,
                new BigDecimal("1.50"),
                new BigDecimal("0.80"),
                360, false, false, false);
        list.add(extreme);
        FilerProfile filer = new FilerProfile("ABCDEFGHIJKLMNOPQRST",
                "Omnibank", "12-3456789", 99999, false);
        HmdaLarFile file = gen.generate(filer, 2026, list);
        var counts = HmdaReportGenerator.editCounts(file);
        assertThat(counts.getOrDefault(EditCategory.Q_QUALITY, 0L)).isGreaterThanOrEqualTo(3L);
    }
}
