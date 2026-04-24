package com.aibench.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer-2 generator: enumerated domain entities for SWIFT MT messages.
 * Each message type gets a full handler module with parser, validator,
 * routing rules, settlement hooks, and a test.
 *
 * <p>Reads the SWIFT catalog from {@code swift-mt-types.csv} and emits into
 * {@code banking-app/generated-swift/message-handler-mt<nnn>}.
 */
public class SwiftMessageHandlerGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.swift";

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<MtSpec> specs = loadSpecs();

        List<String> includes = new ArrayList<>();
        for (MtSpec spec : specs) {
            String moduleName = "message-handler-" + spec.mt.toLowerCase();
            Path moduleDir = outputRoot.resolve(moduleName);
            writeModule(moduleDir, spec);
            includes.add(moduleName);
        }

        System.out.println("Generated " + includes.size() + " SWIFT MT handler modules into "
                + outputRoot.toAbsolutePath());
        for (String inc : includes) {
            System.out.println("include(\"generated-swift:" + inc + "\")");
        }
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) return candidate.resolve("generated-swift");
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/");
    }

    private static List<MtSpec> loadSpecs() throws IOException {
        try (InputStream in = SwiftMessageHandlerGenerator.class
                .getResourceAsStream("/swift-mt-types.csv")) {
            if (in == null) throw new IOException("swift-mt-types.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<MtSpec> out = new ArrayList<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i].trim();
                if (row.isEmpty()) continue;
                String[] f = row.split(",");
                out.add(new MtSpec(
                        f[0], f[1], f[2], f[3],
                        Boolean.parseBoolean(f[4]),
                        Boolean.parseBoolean(f[5]),
                        Boolean.parseBoolean(f[6]),
                        Boolean.parseBoolean(f[7]),
                        new BigDecimal(f[8])
                ));
            }
            return out;
        }
    }

    private static void writeModule(Path moduleDir, MtSpec spec) throws IOException {
        String pkg = BASE_PACKAGE + ".mt" + spec.mt.toLowerCase().replace("mt", "");
        String cls = "Mt" + spec.mt.substring(2);
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path testSrc = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(testSrc);

        writeString(moduleDir.resolve("build.gradle.kts"), SwiftTemplates.buildGradle());
        writeString(src.resolve("package-info.java"),
                SwiftTemplates.packageInfo(pkg, spec));
        writeString(src.resolve(cls + "Message.java"),
                SwiftTemplates.messageRecord(pkg, cls, spec));
        writeString(src.resolve(cls + "Parser.java"),
                SwiftTemplates.parser(pkg, cls, spec));
        writeString(src.resolve(cls + "Validator.java"),
                SwiftTemplates.validator(pkg, cls, spec));
        writeString(src.resolve(cls + "RoutingRules.java"),
                SwiftTemplates.routingRules(pkg, cls, spec));
        writeString(src.resolve(cls + "Handler.java"),
                SwiftTemplates.handler(pkg, cls, spec));
        writeString(testSrc.resolve(cls + "HandlerTest.java"),
                SwiftTemplates.handlerTest(pkg, cls, spec));
    }

    private static void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    record MtSpec(
            String mt, String category, String name, String description,
            boolean isPayment, boolean isStatement, boolean requiresSettlement,
            boolean hasBeneficiary, BigDecimal typicalAmount
    ) {}
}
