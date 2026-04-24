package com.aibench.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer-2 generator: per-SEC-code ACH processor modules. Each NACHA
 * Standard Entry Class (SEC) code has its own authorization rules,
 * return-handling, and addenda format; real bank ACH platforms have
 * dedicated processors per SEC code.
 *
 * <p>Emits into {@code banking-app/generated-nacha/sec-<code>}.
 */
public class NachaSecCodeGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.nacha";

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<SecCode> codes = loadCodes();

        List<String> includes = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SecCode c : codes) {
            if (!seen.add(c.code)) continue;
            String moduleName = "sec-" + c.code.toLowerCase();
            Path moduleDir = outputRoot.resolve(moduleName);
            writeModule(moduleDir, c);
            includes.add(moduleName);
        }

        System.out.println("Generated " + includes.size() + " NACHA SEC processor modules into "
                + outputRoot.toAbsolutePath());
        for (String inc : includes) {
            System.out.println("include(\"generated-nacha:" + inc + "\")");
        }
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) return candidate.resolve("generated-nacha");
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/");
    }

    private static List<SecCode> loadCodes() throws IOException {
        try (InputStream in = NachaSecCodeGenerator.class
                .getResourceAsStream("/nacha-sec-codes.csv")) {
            if (in == null) throw new IOException("nacha-sec-codes.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<SecCode> out = new ArrayList<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i].trim();
                if (row.isEmpty()) continue;
                String[] f = row.split(",");
                out.add(new SecCode(
                        f[0], f[1], f[2], f[3],
                        Boolean.parseBoolean(f[4]),
                        Boolean.parseBoolean(f[5]),
                        Integer.parseInt(f[6]),
                        f[7]
                ));
            }
            return out;
        }
    }

    private static void writeModule(Path moduleDir, SecCode c) throws IOException {
        String pkg = BASE_PACKAGE + "." + c.code.toLowerCase();
        String cls = c.code;
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path testSrc = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(testSrc);

        writeString(moduleDir.resolve("build.gradle.kts"), NachaTemplates.buildGradle());
        writeString(src.resolve("package-info.java"), NachaTemplates.packageInfo(pkg, c));
        writeString(src.resolve(cls + "Entry.java"),
                NachaTemplates.entryRecord(pkg, cls, c));
        writeString(src.resolve(cls + "AuthorizationCheck.java"),
                NachaTemplates.authorizationCheck(pkg, cls, c));
        writeString(src.resolve(cls + "ReturnProcessor.java"),
                NachaTemplates.returnProcessor(pkg, cls, c));
        writeString(src.resolve(cls + "Processor.java"),
                NachaTemplates.processor(pkg, cls, c));
        writeString(testSrc.resolve(cls + "ProcessorTest.java"),
                NachaTemplates.processorTest(pkg, cls, c));
    }

    private static void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    record SecCode(
            String code, String name, String description, String consumerOrCorporate,
            boolean reqAuth, boolean allowsReturn, int returnWindowDays, String typicalUseCase
    ) {}
}
