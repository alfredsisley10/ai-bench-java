package com.aibench.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase C / Layer 3 of the 10M LOC scaling plan
 * (see docs/project-context/SCALING_PLAN.md).
 *
 * <p>Walks every module under {@code banking-app/generated-regional/} and
 * writes an additional {@code <Name>ScenarioMatrixTest.java} alongside the
 * existing profile test. The new test class uses only the public APIs
 * already present (Profile, RateTable, Disclosures, RegulatoryFilings,
 * BranchIntegration) so no production code changes are required.
 *
 * <p>Each generated test file contains roughly 20 @Test methods that
 * exercise rate-band ordering, sales-tax math, disclosure rendering, and
 * channel availability. With 250 regional modules, the run produces
 * ~5,000 new test methods and ~40K LOC of test code.
 *
 * <p>Idempotent: rerunning the generator overwrites the
 * {@code *ScenarioMatrixTest.java} files but leaves all other sources
 * untouched. Run via {@code java com.aibench.codegen.RegionalScenarioTestGenerator}
 * from the {@code banking-app} directory.
 */
public class RegionalScenarioTestGenerator {

    public static void main(String[] args) throws IOException {
        Path regionalRoot = resolveRegionalRoot(args);
        if (!Files.isDirectory(regionalRoot)) {
            throw new IllegalStateException(
                    "generated-regional not found at " + regionalRoot);
        }

        List<Path> moduleDirs;
        try (var stream = Files.list(regionalRoot)) {
            moduleDirs = stream.filter(Files::isDirectory).sorted().toList();
        }

        int generated = 0;
        long totalLoc = 0;
        for (Path moduleDir : moduleDirs) {
            ModuleInfo info = inspectModule(moduleDir);
            if (info == null) continue;
            String src = scenarioTest(info);
            Path testFile = info.testDir.resolve(info.classPrefix + "ScenarioMatrixTest.java");
            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, src, StandardCharsets.UTF_8);
            generated++;
            totalLoc += src.lines().count();
        }

        System.out.println("Generated " + generated + " scenario-matrix test files (~"
                + totalLoc + " LOC) under " + regionalRoot);
    }

    private static Path resolveRegionalRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app").resolve("generated-regional");
            if (Files.isDirectory(candidate)) return candidate;
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/generated-regional");
    }

    /**
     * Looks at the existing main-source tree to recover the package and the
     * shared class-name prefix the generator originally used.
     */
    private static ModuleInfo inspectModule(Path moduleDir) throws IOException {
        Path mainJava = moduleDir.resolve("src/main/java");
        if (!Files.isDirectory(mainJava)) return null;

        Path profileFile;
        try (var stream = Files.walk(mainJava)) {
            profileFile = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Profile.java"))
                    .min(Comparator.comparing(Path::toString))
                    .orElse(null);
        }
        if (profileFile == null) return null;

        String fileName = profileFile.getFileName().toString();
        String classPrefix = fileName.substring(0, fileName.length() - "Profile.java".length());
        String pkg = readPackage(profileFile);
        if (pkg == null) return null;

        Path testDir = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        return new ModuleInfo(pkg, classPrefix, testDir);
    }

    private static String readPackage(Path javaFile) throws IOException {
        for (String line : Files.readAllLines(javaFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
            }
        }
        return null;
    }

    private static String scenarioTest(ModuleInfo info) {
        return ("""
                package %PKG%;

                import java.math.BigDecimal;
                import java.time.YearMonth;
                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                /**
                 * Auto-generated scenario-matrix tests for the %PREFIX% jurisdictional
                 * fork. Generated by {@code com.aibench.codegen.RegionalScenarioTestGenerator}
                 * from the public APIs of the existing {@link %PREFIX%Profile},
                 * {@link %PREFIX%RateTable}, {@link %PREFIX%Disclosures},
                 * {@link %PREFIX%RegulatoryFilings}, and {@link %PREFIX%BranchIntegration}.
                 *
                 * <p>Tests here cover boundary scenarios that the hand-written
                 * {@code %PREFIX%ProfileTest} does not exercise: rate-band tier
                 * monotonicity, sales-tax rounding precision, channel availability
                 * vs. profile flags, multi-quarter regulatory filings.
                 */
                class %PREFIX%ScenarioMatrixTest {

                    @Test
                    void profile_state_code_is_two_letters() {
                        var p = new %PREFIX%Profile();
                        assertThat(p.stateCode()).hasSize(2);
                        assertThat(p.stateName()).isNotBlank();
                    }

                    @Test
                    void profile_region_is_one_of_the_four_us_regions() {
                        var p = new %PREFIX%Profile();
                        assertThat(p.region()).isIn("WEST", "MIDWEST", "SOUTH", "NORTHEAST");
                    }

                    @Test
                    void profile_sales_tax_rate_is_within_us_range() {
                        var p = new %PREFIX%Profile();
                        // No US state has > 11% combined tax; treat the profile rate
                        // as state-only so an upper bound of 12% is comfortably above.
                        assertThat(p.salesTaxRate()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                        assertThat(p.salesTaxRate()).isLessThanOrEqualTo(new BigDecimal("0.12"));
                    }

                    @Test
                    void profile_escheatment_window_is_realistic() {
                        var p = new %PREFIX%Profile();
                        assertThat(p.escheatmentDormancyYears()).isBetween(1, 10);
                    }

                    @Test
                    void profile_no_sales_tax_predicate_is_consistent() {
                        var p = new %PREFIX%Profile();
                        assertThat(p.isNoSalesTaxState())
                                .isEqualTo(p.salesTaxRate().signum() == 0);
                    }

                    @Test
                    void apply_state_fee_tax_zero_in_zero_out() {
                        var p = new %PREFIX%Profile();
                        assertThat(p.applyStateFeeTax(BigDecimal.ZERO))
                                .isEqualByComparingTo(BigDecimal.ZERO);
                    }

                    @Test
                    void apply_state_fee_tax_preserves_two_decimals() {
                        var p = new %PREFIX%Profile();
                        var taxed = p.applyStateFeeTax(new BigDecimal("12.34"));
                        assertThat(taxed.scale()).isEqualTo(2);
                    }

                    @Test
                    void apply_state_fee_tax_is_monotonic_in_input() {
                        var p = new %PREFIX%Profile();
                        var lo = p.applyStateFeeTax(new BigDecimal("5.00"));
                        var hi = p.applyStateFeeTax(new BigDecimal("50.00"));
                        assertThat(hi).isGreaterThanOrEqualTo(lo);
                    }

                    @Test
                    void rate_table_band_count_is_at_least_three() {
                        var rt = new %PREFIX%RateTable();
                        assertThat(rt.bands().size()).isGreaterThanOrEqualTo(3);
                    }

                    @Test
                    void rate_table_apys_are_non_negative_across_buckets() {
                        var rt = new %PREFIX%RateTable();
                        for (String bal : new String[]{"0", "100", "1000", "10000", "100000", "5000000"}) {
                            assertThat(rt.apyFor(new BigDecimal(bal)).signum())
                                    .as("balance " + bal)
                                    .isGreaterThanOrEqualTo(0);
                        }
                    }

                    @Test
                    void rate_table_is_monotonically_non_decreasing() {
                        var rt = new %PREFIX%RateTable();
                        var prev = rt.apyFor(BigDecimal.ZERO);
                        for (String bal : new String[]{"500", "5000", "50000", "500000"}) {
                            var cur = rt.apyFor(new BigDecimal(bal));
                            assertThat(cur).as("balance " + bal).isGreaterThanOrEqualTo(prev);
                            prev = cur;
                        }
                    }

                    @Test
                    void rate_table_top_tier_matches_max_observed_apy() {
                        var rt = new %PREFIX%RateTable();
                        var top = rt.topTierApy();
                        for (String bal : new String[]{"100", "10000", "1000000"}) {
                            assertThat(top).isGreaterThanOrEqualTo(rt.apyFor(new BigDecimal(bal)));
                        }
                    }

                    @Test
                    void rate_sheet_reference_is_traceable() {
                        var rt = new %PREFIX%RateTable();
                        assertThat(rt.rateSheetRef()).isNotBlank();
                        assertThat(rt.effectiveFrom().getYear()).isBetween(2020, 2030);
                    }

                    @Test
                    void disclosures_full_supplement_contains_state_name() {
                        var p = new %PREFIX%Profile();
                        var d = new %PREFIX%Disclosures();
                        assertThat(d.renderFullSupplement()).contains(p.stateName());
                    }

                    @Test
                    void disclosures_supplement_is_not_blank() {
                        var d = new %PREFIX%Disclosures();
                        assertThat(d.renderFullSupplement()).isNotBlank();
                    }

                    @Test
                    void regulatory_filings_all_quarters_produce_output() {
                        var f = new %PREFIX%RegulatoryFilings();
                        for (int month : new int[]{3, 6, 9, 12}) {
                            assertThat(f.quarterlyFilings(YearMonth.of(2026, month)))
                                    .as("quarter ending month " + month)
                                    .isNotEmpty();
                        }
                    }

                    @Test
                    void branch_integration_web_and_mobile_always_active() {
                        var b = new %PREFIX%BranchIntegration();
                        assertThat(b.channelActive("WEB")).isTrue();
                        assertThat(b.channelActive("MOBILE")).isTrue();
                    }

                    @Test
                    void branch_integration_unknown_channel_is_inactive() {
                        var b = new %PREFIX%BranchIntegration();
                        assertThat(b.channelActive("CARRIER_PIGEON")).isFalse();
                    }

                    @Test
                    void branch_integration_atm_is_active_in_every_state() {
                        var b = new %PREFIX%BranchIntegration();
                        assertThat(b.channelActive("ATM")).isTrue();
                    }

                    @Test
                    void state_authority_name_is_present_and_nontrivial() {
                        var p = new %PREFIX%Profile();
                        assertThat(p.stateBankingAuthority()).isNotBlank();
                        assertThat(p.stateBankingAuthority().length()).isGreaterThanOrEqualTo(8);
                    }
                }
                """)
                .replace("%PKG%", info.pkg)
                .replace("%PREFIX%", info.classPrefix);
    }

    private record ModuleInfo(String pkg, String classPrefix, Path testDir) {
        ModuleInfo {
            if (testDir == null) {
                throw new IllegalArgumentException("testDir");
            }
        }
    }
}
