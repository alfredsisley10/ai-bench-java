package com.aibench.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase A of the 10M LOC scaling plan (see docs/project-context/SCALING_PLAN.md).
 *
 * <p>Reads {@code product-variants.csv} and generates one Gradle module per
 * product variant under {@code banking-app/generated/product-<name>}. Each
 * module contains ~7 Java source files, a build.gradle.kts, and a test, for
 * ~800–1000 LOC per variant.
 *
 * <p>Run via: {@code ./gradlew :tooling:codegen:run} (or
 * {@code gradle :run} from inside the tooling/codegen directory).
 * The generator is idempotent — rerunning it overwrites the generated
 * directory but leaves hand-written modules untouched.
 */
public class ProductVariantGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.productvariants";

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<ProductSpec> specs = loadSpecs();

        System.out.println("Generating " + specs.size() + " product variant modules into "
                + outputRoot.toAbsolutePath());

        List<String> gradleIncludes = new ArrayList<>();
        for (ProductSpec spec : specs) {
            String moduleName = "product-" + hyphenate(spec.name);
            Path moduleDir = outputRoot.resolve(moduleName);
            writeModule(moduleDir, spec);
            gradleIncludes.add(moduleName);
        }

        writeAggregateReadme(outputRoot, specs);
        System.out.println("Done. Include in banking-app/settings.gradle.kts:");
        for (String inc : gradleIncludes) {
            System.out.println("  include(\"generated:" + inc + "\")");
        }
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) {
            return Paths.get(args[0]);
        }
        // Default: banking-app/generated when run from the tooling/codegen dir.
        Path cwd = Paths.get("").toAbsolutePath();
        // Walk up until we find banking-app, at most 4 levels.
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) {
                return candidate.resolve("generated");
            }
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/ — pass output root as arg 1");
    }

    private static List<ProductSpec> loadSpecs() throws IOException {
        try (InputStream in = ProductVariantGenerator.class.getResourceAsStream(
                "/product-variants.csv")) {
            if (in == null) throw new IOException("product-variants.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<ProductSpec> specs = new ArrayList<>();
            String[] lines = text.split("\\R");
            List<String> header = csv(lines[0]);
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i];
                if (row.isBlank()) continue;
                List<String> fields = csv(row);
                Map<String, String> m = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) {
                    m.put(header.get(j), fields.get(j));
                }
                specs.add(ProductSpec.from(m));
            }
            return specs;
        }
    }

    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        for (String f : line.split(",")) out.add(f.trim());
        return out;
    }

    private static void writeModule(Path moduleDir, ProductSpec spec) throws IOException {
        String pkg = BASE_PACKAGE + "." + spec.name.toLowerCase();
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path testSrc = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(testSrc);

        writeString(moduleDir.resolve("build.gradle.kts"), Templates.buildGradle());

        writeString(src.resolve(spec.name + "Product.java"),
                Templates.productRecord(pkg, spec));
        writeString(src.resolve(spec.name + "FeeSchedule.java"),
                Templates.feeSchedule(pkg, spec));
        writeString(src.resolve(spec.name + "EligibilityRules.java"),
                Templates.eligibilityRules(pkg, spec));
        writeString(src.resolve(spec.name + "Disclosure.java"),
                Templates.disclosure(pkg, spec));
        writeString(src.resolve(spec.name + "PricingEngine.java"),
                Templates.pricingEngine(pkg, spec));
        writeString(src.resolve(spec.name + "LifecycleEvent.java"),
                Templates.lifecycleEvent(pkg, spec));
        writeString(src.resolve(spec.name + "Service.java"),
                Templates.service(pkg, spec));
        writeString(src.resolve("package-info.java"),
                Templates.packageInfo(pkg, spec));
        writeString(testSrc.resolve(spec.name + "ServiceTest.java"),
                Templates.serviceTest(pkg, spec));
    }

    private static void writeAggregateReadme(Path outputRoot, List<ProductSpec> specs)
            throws IOException {
        Files.createDirectories(outputRoot);
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated product-variant modules\n\n");
        sb.append("Generated by `tooling/codegen/ProductVariantGenerator` from `product-variants.csv`.\n");
        sb.append("**Do not hand-edit** — rerun the generator to modify. See ");
        sb.append("`docs/project-context/SCALING_PLAN.md` for the broader plan.\n\n");
        sb.append("| Module | Category | Segment | Base rate | Min balance |\n");
        sb.append("|---|---|---|---|---|\n");
        for (ProductSpec s : specs) {
            sb.append("| product-").append(hyphenate(s.name)).append(" | ")
                    .append(s.category).append(" | ")
                    .append(s.targetSegment).append(" | ")
                    .append(s.baseRate).append(" | $")
                    .append(s.minBalance).append(" |\n");
        }
        writeString(outputRoot.resolve("README.md"), sb.toString());
    }

    private static String hyphenate(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    private static void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /** Immutable specification for one product variant. */
    record ProductSpec(
            String name,
            String humanName,
            String category,
            String targetSegment,
            String baseRate,
            String minBalance,
            String monthlyFee,
            String feeWaiverBalance,
            String earlyWithdrawalPenaltyDays,
            boolean requiresId,
            int eligibilityAgeMin,
            int eligibilityAgeMax,
            String jurisdiction
    ) {
        static ProductSpec from(Map<String, String> m) {
            return new ProductSpec(
                    Objects.requireNonNull(m.get("name"), "name"),
                    m.get("humanName"),
                    m.get("category"),
                    m.get("targetSegment"),
                    m.get("baseRate"),
                    m.get("minBalance"),
                    m.get("monthlyFee"),
                    m.get("feeWaiverBalance"),
                    m.get("earlyWithdrawalPenaltyDays"),
                    Boolean.parseBoolean(m.get("requiresId")),
                    Integer.parseInt(m.get("eligibilityAgeMin")),
                    Integer.parseInt(m.get("eligibilityAgeMax")),
                    m.get("jurisdiction")
            );
        }
    }
}
