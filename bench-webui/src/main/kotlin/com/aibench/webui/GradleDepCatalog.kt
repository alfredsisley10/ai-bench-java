package com.aibench.webui

import org.springframework.stereotype.Component

/**
 * Curated catalog of every dependency bench-webui + banking-app
 * actually need to resolve at gradle build time, grouped by
 * category so the operator can toggle which categories to validate
 * against the active mirror+proxy on /mirror's "Verify dependencies"
 * panel.
 *
 * Versions are pinned to whatever's in the matching build.gradle.kts
 * AS OF this catalog's authoring. When a dep version bumps, update
 * here too -- a stale catalog would have the validator probing a
 * version the build doesn't actually use, masking real outages.
 *
 * Categories:
 *   - GRADLE_PLUGIN: gradle plugin coordinates that resolve via
 *     plugins.gradle.org or the operator's mirror's plugin layout.
 *     Failure here = no build will start at all.
 *   - SPRING_BOOT: Spring Boot starters bench-webui + banking-app
 *     declare directly. Failure here = compileKotlin/compileJava
 *     can't find spring classes.
 *   - BANKING_TEST: testcontainers / datafaker / etc. that
 *     banking-app's :shared-testing depends on. Failure here = the
 *     AppMap trace generation user reported on Windows
 *     ("Could not resolve all files for ':shared-testing:compileClasspath'
 *      which included references to org.testcontainers...").
 *   - BANKING_RUNTIME: postgres driver, HikariCP, the runtime
 *     deps banking-app needs but bench-webui doesn't.
 *   - JDK_TOOLCHAIN: foojay-disco endpoint. Probed as a plain HTTPS
 *     URL since it's an API, not a Maven coord.
 *   - GRADLE_DIST: the gradle distribution zip the wrapper downloads.
 *     Same reason -- plain HTTPS URL.
 *   - WEBUI_DIRECT: deps bench-webui declares directly (h2,
 *     jackson-module-kotlin, apache-poi, springdoc, commonmark, okhttp).
 *
 * Pragmatic scope: this is the curated minimum that covers the
 * *known failure modes* operators have hit. Transitive deps (the
 * full ~300-entry dependency tree of each starter) are NOT
 * exhaustively listed -- if a starter resolves, gradle then walks
 * its transitive set against the same mirror and any miss surfaces
 * as a build error the operator can copy-paste into the new
 * "Custom" textarea on the Gradle settings page.
 */
@Component
class GradleDepCatalog {

    enum class Category(val displayName: String, val description: String) {
        GRADLE_PLUGIN("Gradle plugins",
            "Plugins resolved via plugins.gradle.org or your mirror's plugin layout"),
        SPRING_BOOT("Spring Boot starters",
            "spring-boot-starter-* coordinates declared directly in build.gradle.kts"),
        BANKING_TEST("Banking-app test deps",
            "testcontainers, datafaker, etc. -- root cause of recent Windows trace-gen failures"),
        BANKING_RUNTIME("Banking-app runtime deps",
            "Database drivers, connection pools the live banking-app needs at runtime"),
        WEBUI_DIRECT("bench-webui direct deps",
            "Non-spring deps bench-webui declares: h2, jackson-kotlin, apache-poi, etc."),
        JDK_TOOLCHAIN("JDK toolchain (foojay)",
            "api.foojay.io endpoint used by gradle's foojay-resolver-convention to download JDKs"),
        GRADLE_DIST("Gradle distribution",
            "services.gradle.org -- where the gradle wrapper downloads the gradle-X.Y.Z-bin.zip"),
        ;
    }

    /** Spring Boot version bench-webui + banking-app are pinned to. */
    private val springBootVersion = "3.5.14"
    private val kotlinVersion = "2.0.20"
    private val gradleVersion = "9.4.1"

    data class Entry(
        val category: Category,
        val coord: String,           // groupId:artifactId:version (Maven) or full URL (non-Maven probes)
        val description: String,
        val pomPath: String,         // Maven repo path (.pom) when category is Maven; "" for URLs
        val isUrl: Boolean = false,  // true when coord IS the URL to probe (jdk toolchain, gradle dist)
        // True for coordinates whose published artifact is the POM
        // itself with no companion .jar -- BOMs (e.g.
        // testcontainers-bom, spring-boot-dependencies) live here.
        // The validator probes the JAR in addition to the POM for
        // every other entry so "POM serves but JAR doesn't" mirror
        // misconfigs (the testcontainers junit-jupiter case where
        // gradle dep validation passes but the real build fails on
        // junit-jupiter-1.20.1.jar) actually surface in the table.
        val isPomOnly: Boolean = false
    ) {
        /** Companion .jar path (or null if the coord is POM-only/URL-only). */
        val jarPath: String? = if (isUrl || isPomOnly) null
            else pomPath.removeSuffix(".pom") + ".jar"
    }

    /** Build the (groupId, artifactId, version) triple's POM repo
     *  path: `groupId/artifactId/version/artifactId-version.pom`. */
    private fun mavenPomPath(coord: String): String {
        val parts = coord.split(":")
        if (parts.size < 3) return ""
        val (g, a, v) = Triple(parts[0], parts[1], parts[2])
        return g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ".pom"
    }

    private fun maven(category: Category, coord: String, description: String): Entry {
        // POM-only detection: skips the companion-JAR probe for
        // coords whose published artifact is the POM itself with no
        // meaningful JAR. Three buckets:
        //   - BOMs ("-bom" / "-dependencies" suffix): packaging=pom
        //     by design, no JAR ever published.
        //   - Gradle plugin markers ("*.gradle.plugin" suffix): the
        //     marker artifact is a tiny POM that points at the real
        //     plugin jar at the rewrite-target coord. Many Maven
        //     repos don't publish a marker JAR at all -- gradle's
        //     plugin resolution doesn't need it. Probing the marker
        //     JAR was reporting "JAR fetch returned HTTP -1" false
        //     negatives on perfectly valid corp Artifactory mirrors;
        //     PR #37 already covers the "real plugin jar resolves"
        //     check via the rewrite-target probe in
        //     GradleDepValidator.probeOne.
        // False positives in either heuristic just suppress a JAR
        // probe that would have failed anyway, so the cost is zero.
        val artifactId = coord.split(":").getOrNull(1).orEmpty()
        val isBomLike = artifactId.endsWith("-bom") ||
                        artifactId.endsWith("-dependencies")
        val isPluginMarker = artifactId.endsWith(".gradle.plugin")
        return Entry(category, coord, description, mavenPomPath(coord),
            isUrl = false, isPomOnly = isBomLike || isPluginMarker)
    }

    private fun url(category: Category, url: String, description: String) =
        Entry(category, url, description, "", isUrl = true)

    val entries: List<Entry> = listOf(
        // --- Gradle plugins ----------------------------------------------
        // Each plugin id has a corresponding Maven marker artifact under
        // <id>:<id>.gradle.plugin so the same POM-resolution probe can
        // verify it (this is how the gradle plugin portal actually
        // exposes plugins via Maven repos).
        maven(Category.GRADLE_PLUGIN,
            "org.springframework.boot:org.springframework.boot.gradle.plugin:$springBootVersion",
            "Spring Boot gradle plugin (drives bootRun, bootJar)"),
        maven(Category.GRADLE_PLUGIN,
            "io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:1.1.6",
            "Spring dependency-management plugin (BOM imports)"),
        maven(Category.GRADLE_PLUGIN,
            "org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:$kotlinVersion",
            "Kotlin JVM compiler plugin"),
        maven(Category.GRADLE_PLUGIN,
            "org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:$kotlinVersion",
            "Kotlin Spring plugin (open final classes for Spring proxies)"),
        maven(Category.GRADLE_PLUGIN,
            "org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:$kotlinVersion",
            "Kotlinx serialization compiler plugin"),
        maven(Category.GRADLE_PLUGIN,
            "com.appland.appmap:com.appland.appmap.gradle.plugin:1.2.0",
            "AppMap test instrumentation plugin"),
        maven(Category.GRADLE_PLUGIN,
            "org.gradle.toolchains.foojay-resolver-convention:org.gradle.toolchains.foojay-resolver-convention.gradle.plugin:1.0.0",
            "Foojay JDK auto-provisioning convention"),

        // --- Spring Boot starters (banking-app + bench-webui) -----------
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter:$springBootVersion",
            "Spring Boot core starter"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter-web:$springBootVersion",
            "Spring MVC + embedded Tomcat"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter-actuator:$springBootVersion",
            "/actuator/* endpoints (health, info)"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter-data-jpa:$springBootVersion",
            "Spring Data JPA + Hibernate"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter-thymeleaf:$springBootVersion",
            "Thymeleaf template engine"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter-security:$springBootVersion",
            "Spring Security (banking-app)"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-starter-test:$springBootVersion",
            "Spring Boot test framework"),
        maven(Category.SPRING_BOOT, "org.springframework.boot:spring-boot-dependencies:$springBootVersion",
            "Spring Boot BOM (transitive version pins)"),

        // --- Banking-app test deps (the Windows failure category) -------
        maven(Category.BANKING_TEST, "org.testcontainers:testcontainers:1.20.6",
            "Testcontainers core"),
        maven(Category.BANKING_TEST, "org.testcontainers:postgresql:1.20.6",
            "Testcontainers Postgres module"),
        maven(Category.BANKING_TEST, "org.testcontainers:junit-jupiter:1.20.6",
            "Testcontainers JUnit 5 integration"),
        maven(Category.BANKING_TEST, "org.testcontainers:testcontainers-bom:1.20.6",
            "Testcontainers BOM"),
        maven(Category.BANKING_TEST, "net.datafaker:datafaker:2.4.0",
            "Datafaker for test fixture generation"),

        // --- Banking-app runtime deps ----------------------------------
        maven(Category.BANKING_RUNTIME, "org.postgresql:postgresql:42.7.4",
            "Postgres JDBC driver"),
        maven(Category.BANKING_RUNTIME, "com.zaxxer:HikariCP:5.1.0",
            "HikariCP connection pool"),

        // --- bench-webui direct deps -----------------------------------
        maven(Category.WEBUI_DIRECT, "com.h2database:h2:2.3.232",
            "H2 file-mode DB for run persistence"),
        maven(Category.WEBUI_DIRECT, "com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2",
            "Jackson Kotlin module (default-param data class deserialize)"),
        maven(Category.WEBUI_DIRECT, "org.apache.poi:poi-ooxml:5.3.0",
            "Apache POI for XLSX export"),
        maven(Category.WEBUI_DIRECT, "org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0",
            "OpenAPI + Swagger UI"),
        maven(Category.WEBUI_DIRECT, "org.commonmark:commonmark:0.22.0",
            "CommonMark markdown renderer"),
        maven(Category.WEBUI_DIRECT, "com.squareup.okhttp3:okhttp:4.12.0",
            "OkHttp client"),
        maven(Category.WEBUI_DIRECT, "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3",
            "kotlinx-serialization JSON"),

        // --- JDK toolchain provisioning --------------------------------
        url(Category.JDK_TOOLCHAIN,
            "https://api.foojay.io/disco/v3.0/distributions",
            "Foojay disco API root -- gradle hits this to enumerate JDK distributions"),

        // --- Gradle wrapper distribution -------------------------------
        url(Category.GRADLE_DIST,
            "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip",
            "Gradle $gradleVersion distribution zip the wrapper downloads")
    )

    /** Group entries by category for the UI's per-category toggles. */
    fun byCategory(): Map<Category, List<Entry>> =
        entries.groupBy { it.category }
            .toSortedMap(compareBy { it.ordinal })
}
