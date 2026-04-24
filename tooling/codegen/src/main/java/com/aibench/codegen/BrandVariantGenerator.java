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
 * Fourth-layer generator: crosses foundation products with multiple brands.
 * Real banks own several consumer-facing brands that share back-office
 * infrastructure. Each brand has distinct copy, onboarding flow, pricing
 * policy, and regulatory persona.
 *
 * <p>Emits into {@code banking-app/generated-brands/}.
 */
public class BrandVariantGenerator {

    private static final String BASE_PACKAGE = "com.omnibank.brand";

    private static final List<String> FOUNDATIONS = List.of(
            "StandardSavings", "BasicChecking", "HighYieldSavings",
            "EssentialChecking", "DigitalOnlyChecking", "RewardsChecking",
            "PremierMoneyMarket", "TraditionalIra", "RothIra",
            "SmallBizChecking", "CommercialChecking",
            "TeenChecking", "SeniorChecking", "VeteranChecking",
            "StudentChecking", "CdLadder12Month", "CdLadder24Month",
            "ChristmasClub", "VacationClub", "HomeDownPaymentSavings");

    public static void main(String[] args) throws IOException {
        Path outputRoot = resolveOutputRoot(args);
        List<BrandSpec> brands = loadBrands();

        int total = 0;
        List<String> includes = new ArrayList<>();
        for (BrandSpec brand : brands) {
            for (String foundation : FOUNDATIONS) {
                if (!brandOffersProduct(brand, foundation)) continue;
                String moduleName = "product-" + hyphenate(foundation) + "-" + brand.code.toLowerCase();
                Path moduleDir = outputRoot.resolve(moduleName);
                writeModule(moduleDir, foundation, brand);
                includes.add(moduleName);
                total++;
            }
        }

        System.out.println("Generated " + total + " brand variants across "
                + brands.size() + " brands × up-to-" + FOUNDATIONS.size()
                + " products into " + outputRoot.toAbsolutePath());
        for (String inc : includes) {
            System.out.println("include(\"generated-brands:" + inc + "\")");
        }
    }

    /** Not every brand sells every product. Constrain to realistic overlap. */
    private static boolean brandOffersProduct(BrandSpec brand, String product) {
        return switch (brand.code) {
            case "OMNIBANK", "BLUEWATERBANK" -> true;
            case "OMNIDIRECT" -> !(product.contains("Cd") || product.contains("Christmas")
                    || product.contains("Commercial"));
            case "SILVERBANK" -> product.contains("Savings") || product.contains("Checking")
                    || product.contains("Cd") || product.contains("Ira");
            case "STARTUPFI" -> product.contains("Biz") || product.contains("Commercial")
                    || product.contains("BasicChecking") || product.contains("HighYieldSavings");
            case "LEGACYTRUST" -> product.contains("Premier") || product.contains("Ira")
                    || product.contains("Cd") || product.contains("HighYield");
            case "FUTUREBANK" -> product.contains("Teen") || product.contains("Student")
                    || product.contains("Youth") || product.contains("Christmas")
                    || product.contains("HomeDown") || product.contains("Savings");
            default -> true;
        };
    }

    private static Path resolveOutputRoot(String[] args) {
        if (args.length > 0) return Paths.get(args[0]);
        Path cwd = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = cwd.resolve("banking-app");
            if (Files.isDirectory(candidate)) return candidate.resolve("generated-brands");
            if (cwd.getParent() == null) break;
            cwd = cwd.getParent();
        }
        throw new IllegalStateException("Cannot find banking-app/");
    }

    private static List<BrandSpec> loadBrands() throws IOException {
        try (InputStream in = BrandVariantGenerator.class.getResourceAsStream("/brands.csv")) {
            if (in == null) throw new IOException("brands.csv not on classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<BrandSpec> out = new ArrayList<>();
            String[] lines = text.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                String row = lines[i].trim();
                if (row.isEmpty()) continue;
                String[] f = row.split(",");
                out.add(new BrandSpec(
                        f[0], f[1], f[2], f[3], f[4],
                        Boolean.parseBoolean(f[5]),
                        Integer.parseInt(f[6]),
                        Boolean.parseBoolean(f[7]),
                        Boolean.parseBoolean(f[8]),
                        Integer.parseInt(f[9])
                ));
            }
            return out;
        }
    }

    private static void writeModule(Path moduleDir, String foundation, BrandSpec brand)
            throws IOException {
        String brandCap = capitalize(brand.code.toLowerCase());
        String className = foundation + brandCap;
        String pkg = BASE_PACKAGE + "." + foundation.toLowerCase() + "." + brand.code.toLowerCase();
        Path src = moduleDir.resolve("src/main/java/" + pkg.replace('.', '/'));
        Path testSrc = moduleDir.resolve("src/test/java/" + pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(testSrc);

        writeString(moduleDir.resolve("build.gradle.kts"), BrandTemplates.buildGradle());
        writeString(src.resolve("package-info.java"),
                BrandTemplates.packageInfo(pkg, foundation, brand));
        writeString(src.resolve(className + "BrandProfile.java"),
                BrandTemplates.brandProfile(pkg, className, foundation, brand));
        writeString(src.resolve(className + "OnboardingFlow.java"),
                BrandTemplates.onboardingFlow(pkg, className, foundation, brand));
        writeString(src.resolve(className + "PricingOverride.java"),
                BrandTemplates.pricingOverride(pkg, className, foundation, brand));
        writeString(src.resolve(className + "MarketingCopy.java"),
                BrandTemplates.marketingCopy(pkg, className, foundation, brand));
        writeString(src.resolve(className + "CustomerCommunication.java"),
                BrandTemplates.customerCommunication(pkg, className, foundation, brand));
        writeString(testSrc.resolve(className + "BrandProfileTest.java"),
                BrandTemplates.brandProfileTest(pkg, className, foundation, brand));
    }

    private static String hyphenate(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    record BrandSpec(
            String code, String displayName, String tagline, String primarySegment,
            String primaryColor, boolean digitalOnly, int minAge, boolean premiumBrand,
            boolean supportsForeignCurrency, int brandFounded
    ) {}
}
