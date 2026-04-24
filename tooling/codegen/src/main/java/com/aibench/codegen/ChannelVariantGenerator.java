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
 * Third-layer generator: crosses foundation products with delivery channels.
 * Each product × channel pair is a full module with channel-specific
 * authentication, session management, error handling, timeout policies,
 * and feature gating.
 *
 * <p>Realistic banks run distinct binaries per channel (the web handler is
 * not the same deploy as the IVR handler), so forking at this dimension
 * produces authentic enterprise structure.
 *
 * <p>Emits into {@code banking-app/generated-channels/}.
 */
public class ChannelVariantGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.channel";

    /** Foundation products to cross with channels. */
    private static final List<String> FOUNDATIONS = List.of(
            "StandardSavings", "BasicChecking", "HighYieldSavings",
            "EssentialChecking", "DigitalOnlyChecking", "RewardsChecking",
            "PremierMoneyMarket", "TraditionalIra", "RothIra",
            "SmallBizChecking", "CommercialChecking",
            "TeenChecking", "SeniorChecking", "VeteranChecking",
            "StudentChecking");

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<ChannelSpec> channels = loadChannels();

        int total = 0;
        List<String> includes = new ArrayList<>();
        for (String foundation : FOUNDATIONS) {
            for (ChannelSpec channel : channels) {
                String moduleName = "product-" + hyphenate(foundation) + "-" + channel.code.toLowerCase().replace("_", "-");
                Path moduleDir = outputRoot.resolve(moduleName);
                writeModule(moduleDir, foundation, channel);
                includes.add(moduleName);
                total++;
            }
        }

        System.out.println("Generated " + total + " channel variants across "
                + FOUNDATIONS.size() + " products × " + channels.size() + " channels into "
                + outputRoot.toAbsolutePath());
        for (String inc : includes) {
            System.out.println("include(\"generated-channels:" + inc + "\")");
        }
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) {
                return candidate.resolve("generated-channels");
            }
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/");
    }

    private static List<ChannelSpec> loadChannels() throws IOException {
        try (InputStream in = ChannelVariantGenerator.class.getResourceAsStream(
                "/channels.csv")) {
            if (in == null) throw new IOException("channels.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<ChannelSpec> out = new ArrayList<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i].trim();
                if (row.isEmpty()) continue;
                String[] f = row.split(",");
                out.add(new ChannelSpec(
                        f[0], f[1], f[2], f[3],
                        Integer.parseInt(f[4]),
                        f[5],
                        Boolean.parseBoolean(f[6]),
                        Boolean.parseBoolean(f[7]),
                        Boolean.parseBoolean(f[8]),
                        Boolean.parseBoolean(f[9])
                ));
            }
            return out;
        }
    }

    private static void writeModule(Path moduleDir, String foundation, ChannelSpec channel)
            throws IOException {
        String ch = initCap(channel.code.toLowerCase().replace("_", ""));
        String className = foundation + ch;
        String pkg = BASE_PACKAGE + "." + foundation.toLowerCase() + "." + channel.code.toLowerCase();
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path testSrc = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(testSrc);

        writeString(moduleDir.resolve("build.gradle.kts"), ChannelTemplates.buildGradle());
        writeString(src.resolve("package-info.java"),
                ChannelTemplates.packageInfo(pkg, foundation, channel));
        writeString(src.resolve(className + "SessionConfig.java"),
                ChannelTemplates.sessionConfig(pkg, className, foundation, channel));
        writeString(src.resolve(className + "AuthenticationPolicy.java"),
                ChannelTemplates.authenticationPolicy(pkg, className, foundation, channel));
        writeString(src.resolve(className + "UiCopy.java"),
                ChannelTemplates.uiCopy(pkg, className, foundation, channel));
        writeString(src.resolve(className + "EventTracker.java"),
                ChannelTemplates.eventTracker(pkg, className, foundation, channel));
        writeString(src.resolve(className + "ErrorTranslator.java"),
                ChannelTemplates.errorTranslator(pkg, className, foundation, channel));
        writeString(src.resolve(className + "FeatureGating.java"),
                ChannelTemplates.featureGating(pkg, className, foundation, channel));
        writeString(testSrc.resolve(className + "SessionConfigTest.java"),
                ChannelTemplates.sessionConfigTest(pkg, className, foundation, channel));
    }

    private static String hyphenate(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    private static String initCap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    record ChannelSpec(
            String code, String displayName, String type, String deviceClass,
            int latencyTargetMs, String authStrength, boolean supportsSpanish,
            boolean supportsOffline, boolean canOpenAccount, boolean canCloseAccount
    ) {}
}
