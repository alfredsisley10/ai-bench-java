package com.aibench.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Second-layer generator: produces per-state regional forks of a small set of
 * foundation products. Each fork is a full module with state-specific
 * disclosure overrides, Reg DD variations, and jurisdiction rules.
 *
 * <p>This is intentionally a different entry point from
 * {@link ProductVariantGenerator} so the two layers can evolve independently.
 * Each state fork is ~600–800 LOC.
 *
 * <p>Emits into {@code banking-app/generated-regional/product-<prod>-<state>/}.
 */
public class RegionalForkGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.regional";

    /** Foundation products to fork per state. Keep small — N×M blows up fast. */
    private static final List<String> FOUNDATIONS = List.of(
            "StandardSavings", "BasicChecking", "HighYieldSavings",
            "EssentialChecking", "DigitalOnlyChecking");

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<StateSpec> states = loadStates();

        int total = 0;
        List<String> includes = new ArrayList<>();
        for (String foundation : FOUNDATIONS) {
            for (StateSpec state : states) {
                String moduleName = "product-" + hyphenate(foundation) + "-" + state.code.toLowerCase();
                Path moduleDir = outputRoot.resolve(moduleName);
                writeModule(moduleDir, foundation, state);
                includes.add(moduleName);
                total++;
            }
        }

        System.out.println("Generated " + total + " regional forks across "
                + FOUNDATIONS.size() + " products × " + states.size() + " states into "
                + outputRoot.toAbsolutePath());
        System.out.println("Include in banking-app/settings.gradle.kts:");
        for (String inc : includes) {
            System.out.println("  include(\"generated-regional:" + inc + "\")");
        }
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) {
                return candidate.resolve("generated-regional");
            }
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/");
    }

    private static List<StateSpec> loadStates() throws IOException {
        try (InputStream in = RegionalForkGenerator.class.getResourceAsStream(
                "/us-states.csv")) {
            if (in == null) throw new IOException("us-states.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<StateSpec> out = new ArrayList<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i].trim();
                if (row.isEmpty()) continue;
                String[] f = row.split(",");
                out.add(new StateSpec(f[0], f[1], f[2], f[3], f[4]));
            }
            return out;
        }
    }

    private static void writeModule(Path moduleDir, String foundation, StateSpec state)
            throws IOException {
        String className = foundation + state.code;
        String pkg = BASE_PACKAGE + "." + foundation.toLowerCase() + "." + state.code.toLowerCase();
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path testSrc = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(testSrc);

        writeString(moduleDir.resolve("build.gradle.kts"), RegionalTemplates.buildGradle());
        writeString(src.resolve(className + "Profile.java"),
                RegionalTemplates.profile(pkg, className, foundation, state));
        writeString(src.resolve(className + "Disclosures.java"),
                RegionalTemplates.disclosures(pkg, className, foundation, state));
        writeString(src.resolve(className + "RegulatoryFilings.java"),
                RegionalTemplates.regulatoryFilings(pkg, className, foundation, state));
        writeString(src.resolve(className + "RateTable.java"),
                RegionalTemplates.rateTable(pkg, className, foundation, state));
        writeString(src.resolve(className + "BranchIntegration.java"),
                RegionalTemplates.branchIntegration(pkg, className, foundation, state));
        writeString(src.resolve("package-info.java"),
                RegionalTemplates.packageInfo(pkg, foundation, state));
        writeString(testSrc.resolve(className + "ProfileTest.java"),
                RegionalTemplates.profileTest(pkg, className, foundation, state));
    }

    private static String hyphenate(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    private static void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    record StateSpec(String code, String name, String region, String salesTaxRate, String fdicRegion) {}
}
