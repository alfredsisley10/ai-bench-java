plugins {
    java
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.appland.appmap") version "1.+" apply false
}

val appmapEnabled = (findProperty("appmap_enabled") as String?)?.toBoolean() ?: false

allprojects {
    group = "com.omnibank"
    version = "1.0.0-SNAPSHOT"

    repositories {
        // Repository selection mirrors what the bench-webui /proxy form
        // has configured, propagated here as JVM system properties:
        //  - enterprise.sim.mirror set  → use the corp mirror first,
        //    fall back to direct Maven Central for anything missing
        //  - enterprise.sim.mirror unset → direct Maven Central only
        //  - https.proxyHost set        → JVM auto-routes BOTH paths
        //    through the corp proxy (no extra wiring here)
        // Bypass-mirror toggle on /proxy clears enterprise.sim.mirror
        // for the subprocess so we land in the else branch.
        val mirror = System.getProperty("enterprise.sim.mirror")
        if (mirror != null) {
            val mirrorUser = System.getProperty("mirrorUsername")
            val mirrorPass = System.getProperty("mirrorPassword")
            // Mirror first — the corp Artifactory virtual proxies
            // Maven Central content directly at the bare URL.
            maven {
                url = uri(mirror)
                isAllowInsecureProtocol = true
                if (mirrorUser != null && mirrorPass != null) {
                    credentials { username = mirrorUser; password = mirrorPass }
                }
            }
            // Local enterprise-sim layout fallback ($mirror/maven-central/) —
            // kept so run-canary.ps1 and the rest of the sim toolkit work
            // in their existing sub-path layout.
            maven {
                url = uri("$mirror/maven-central/")
                isAllowInsecureProtocol = true
                if (mirrorUser != null && mirrorPass != null) {
                    credentials { username = mirrorUser; password = mirrorPass }
                }
            }
            // Direct Maven Central as a last-ditch fallback for any
            // dep the mirror doesn't carry. Goes through https.proxyHost
            // automatically when a corp proxy is configured.
            mavenCentral()
        } else {
            mavenCentral()
        }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    if (appmapEnabled) {
        apply(plugin = "com.appland.appmap")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // JDK 25 — the current LTS as of late 2025. Operators on
            // older machines without a 25 install will see foojay-resolver
            // auto-download Temurin 25 into ~/.gradle/jdks on first run.
            // The WebUI's "Verify Java" panel surfaces the toolchain
            // requirement so the gap is obvious before kicking off a
            // multi-minute benchmark. Past floors that drove earlier
            // bumps (kept here so the rationale doesn't get lost): 17 →
            // 21 was needed for type patterns in switch (JEP 441,
            // finalized in 21); 21 → 25 picks up the latest LTS so the
            // toolchain matches what most enterprise builds will pin to.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
            mavenBom("org.testcontainers:testcontainers-bom:1.20.1")
        }
    }

    dependencies {
        val impl = "implementation"
        val test = "testImplementation"
        val testRuntime = "testRuntimeOnly"
        add(impl, "org.slf4j:slf4j-api")
        add(impl, "jakarta.annotation:jakarta.annotation-api")
        add(test, "org.springframework.boot:spring-boot-starter-test")
        add(test, "org.assertj:assertj-core")
        add(test, "org.junit.jupiter:junit-jupiter")
        // Gradle 9 dropped the auto-detection that pulled
        // junit-platform-launcher onto the test runtime classpath when
        // a Jupiter test was present; without this explicit declaration
        // the test executor crashes with "Failed to load JUnit Platform".
        add(testRuntime, "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        // AppMap Gradle plugin wires the agent automatically when applied above.
    }
}
