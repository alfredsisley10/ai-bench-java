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
 * Phase D / Layer 4 of the 10M LOC scaling plan
 * (see docs/project-context/SCALING_PLAN.md).
 *
 * <p>Reads {@code legacy-shadows.csv} and generates one Gradle module per
 * retired subsystem under {@code banking-app/generated-legacy/legacy-<name>}.
 * Each module contains six deprecated, never-called Java classes that mimic
 * the shape of a real retired bank service: a deprecated facade, a
 * deprecated entity, a deprecated DAO, a deprecated configuration class, a
 * deprecated event listener, and a deprecated batch driver. They compile,
 * are wired into Gradle, and bear {@code @Deprecated} plus
 * {@code // DO NOT MODIFY — retired YYYY (REPLACEMENT, ticket TICKET-####)}
 * banners so an LLM cannot tell they are dead until it traces the call
 * sites.
 *
 * <p>This is the "legacy ballast" that real megabank monorepos accumulate
 * over decades. The generator is idempotent — rerunning it overwrites the
 * generated-legacy directory but leaves hand-written modules untouched.
 *
 * <p>Run via {@code java com.aibench.codegen.LegacyShadowGenerator} from
 * the {@code banking-app} directory.
 */
public class LegacyShadowGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.legacy";

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<ShadowSpec> specs = loadSpecs();

        System.out.println("Generating " + specs.size() + " legacy shadow modules into "
                + outputRoot.toAbsolutePath());

        // Wipe any prior generation so renames don't leave orphans behind.
        if (Files.isDirectory(outputRoot)) {
            try (var stream = Files.walk(outputRoot)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }

        List<String> gradleIncludes = new ArrayList<>();
        long totalLoc = 0;
        for (ShadowSpec spec : specs) {
            // Strip the "Legacy" prefix so module names don't double up.
            String stem = spec.name().startsWith("Legacy")
                    ? spec.name().substring("Legacy".length()) : spec.name();
            String moduleName = "legacy-" + hyphenate(stem);
            Path moduleDir = outputRoot.resolve(moduleName);
            totalLoc += writeModule(moduleDir, spec);
            gradleIncludes.add("generated-legacy:" + moduleName);
        }

        writeAggregateReadme(outputRoot, specs);
        System.out.println("Done. Generated approximately " + totalLoc + " LOC across "
                + specs.size() + " modules.");
        System.out.println("Add the following includes to banking-app/settings.gradle.kts:");
        for (String inc : gradleIncludes) {
            System.out.println("  include(\"" + inc + "\")");
        }
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) return candidate.resolve("generated-legacy");
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/ — pass output root as arg 1");
    }

    private static List<ShadowSpec> loadSpecs() throws IOException {
        try (InputStream in = LegacyShadowGenerator.class.getResourceAsStream(
                "/legacy-shadows.csv")) {
            if (in == null) throw new IOException("legacy-shadows.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<ShadowSpec> specs = new ArrayList<>();
            String[] lines = text.split("\\R");
            List<String> header = csv(lines[0]);
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i];
                if (row.isBlank()) continue;
                List<String> fields = csv(row);
                Map<String, String> m = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) m.put(header.get(j), fields.get(j));
                specs.add(ShadowSpec.from(m));
            }
            return specs;
        }
    }

    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        for (String f : line.split(",")) out.add(f.trim());
        return out;
    }

    private static long writeModule(Path moduleDir, ShadowSpec spec) throws IOException {
        String pkg = BASE_PACKAGE + "." + spec.subsystem().replace("-", "") + "."
                + spec.name().toLowerCase();
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);

        long loc = 0;
        loc += writeString(moduleDir.resolve("build.gradle.kts"), LegacyTemplates.buildGradle());
        loc += writeString(src.resolve("package-info.java"),
                LegacyTemplates.packageInfo(pkg, spec));
        loc += writeString(src.resolve(spec.name() + "Facade.java"),
                LegacyTemplates.facade(pkg, spec));
        loc += writeString(src.resolve(spec.name() + "Entity.java"),
                LegacyTemplates.entity(pkg, spec));
        loc += writeString(src.resolve(spec.name() + "Dao.java"),
                LegacyTemplates.dao(pkg, spec));
        loc += writeString(src.resolve(spec.name() + "Configuration.java"),
                LegacyTemplates.configuration(pkg, spec));
        loc += writeString(src.resolve(spec.name() + "EventListener.java"),
                LegacyTemplates.eventListener(pkg, spec));
        loc += writeString(src.resolve(spec.name() + "BatchDriver.java"),
                LegacyTemplates.batchDriver(pkg, spec));
        return loc;
    }

    private static void writeAggregateReadme(Path outputRoot, List<ShadowSpec> specs)
            throws IOException {
        Files.createDirectories(outputRoot);
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated legacy-shadow modules (Phase D, Layer 4)\n\n");
        sb.append("Generated by `tooling/codegen/LegacyShadowGenerator` from ");
        sb.append("`legacy-shadows.csv`. Each module mimics a real retired ");
        sb.append("bank subsystem that a megabank would still carry in its ");
        sb.append("monorepo years after the replacement landed.\n\n");
        sb.append("**Do not hand-edit** — rerun the generator to modify. ");
        sb.append("These modules are intentionally ballast: they compile, ");
        sb.append("are wired into Gradle, and look just like real retired ");
        sb.append("code (deprecated annotations, `// DO NOT MODIFY` banners, ");
        sb.append("stale comments referencing extinct change tickets) so an ");
        sb.append("LLM cannot tell they are dead without tracing call sites.\n\n");
        sb.append("| Module | Subsystem | Retired | Replaced by | Ticket |\n");
        sb.append("|---|---|---|---|---|\n");
        for (ShadowSpec s : specs) {
            sb.append("| legacy-").append(hyphenate(s.name())).append(" | ")
                    .append(s.subsystem()).append(" | ")
                    .append(s.retiredYear()).append(" | ")
                    .append(s.replacementName()).append(" | ")
                    .append(s.ticketRef()).append(" |\n");
        }
        writeString(outputRoot.resolve("README.md"), sb.toString());
    }

    private static String hyphenate(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    private static long writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return content.lines().count();
    }

    /** Immutable spec for one retired subsystem. */
    record ShadowSpec(
            String name,
            String humanName,
            String subsystem,
            int retiredYear,
            String replacementName,
            String ticketRef
    ) {
        static ShadowSpec from(Map<String, String> m) {
            return new ShadowSpec(
                    Objects.requireNonNull(m.get("name"), "name"),
                    m.get("humanName"),
                    m.get("subsystem"),
                    Integer.parseInt(m.get("retiredYear")),
                    m.get("replacementName"),
                    m.get("ticketRef")
            );
        }
    }
}
