plugins {
    java
    id("org.springframework.boot") version "3.5.14" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    // Pinned to 1.2.0 -- the version bench-webui's harness was developed
    // against. The earlier `1.+` floater could resolve to different
    // patch levels across hosts (and across Maven cache states), making
    // "works on my machine" tracebacks harder to triage. Re-pin only
    // after re-verifying AppMap CLI compatibility on each target OS.
    id("com.appland.appmap") version "1.2.0" apply false
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
        // centralViaMirror = true means corp policy blocks direct
        // egress to repo.maven.apache.org (typically the corp proxy
        // 403s on it); set by bench-webui /mirror's
        // "Maven Central via mirror only" toggle. When on, we DROP
        // the mavenCentral() last-ditch fallback below so gradle
        // never tries the blocked upstream -- otherwise gradle's
        // resolution logic falls through to it on any mirror-miss
        // and the build fails on the proxy 403.
        val centralViaMirror = System.getProperty("centralViaMirror") == "true"
        // Optional second virtual on the same corp Artifactory --
        // the canonical "external proxy" virtual Artifactory
        // operators carve out for Maven Central content. Distinct
        // from the primary mirror, which usually carries internal
        // libs + plugins. When set, added as its own repo source so
        // gradle resolves Central content from there directly
        // (matches the corp routing intent: internal vs external
        // virtuals stay separate). Same Artifactory creds.
        val externalVirtual = System.getProperty("mavenExternalVirtual")
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
            // Dedicated maven-external-virtual when configured. Sits
            // alongside (not instead of) the primary mirror so a
            // miss on internal artifacts in the external virtual
            // still falls through to the next repo declaration.
            if (!externalVirtual.isNullOrBlank()) {
                maven {
                    url = uri(externalVirtual)
                    isAllowInsecureProtocol = true
                    if (mirrorUser != null && mirrorPass != null) {
                        credentials { username = mirrorUser; password = mirrorPass }
                    }
                }
            }
            // Direct Maven Central as a last-ditch fallback for any
            // dep the mirror doesn't carry. Goes through https.proxyHost
            // automatically when a corp proxy is configured.
            // Skipped when centralViaMirror is set -- see comment on
            // the property declaration above.
            if (!centralViaMirror) {
                mavenCentral()
            }
        } else {
            mavenCentral()
        }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    if (appmapEnabled) {
        // The com.appland.appmap plugin (1.2.0) registers an
        // AppMapTask extends Test and uses its doLast to wire the
        // AppMap Java agent into the regular test task. That contract
        // breaks when the AppMapTask gets NO-SOURCE'd (which it does
        // for almost every subproject because the plugin doesn't
        // auto-copy testClassesDirs from the test task) — doLast
        // never runs, the agent never attaches, the test task runs
        // green but produces no .appmap.json files. Apply the plugin
        // for the side benefit of the appmapAgent configuration
        // (which resolves the agent jar via Maven), then bypass its
        // broken task wiring and attach the agent to every test task
        // ourselves. This is what makes "Record AppMap from tests"
        // actually produce traces on a fresh checkout.
        apply(plugin = "com.appland.appmap")
        afterEvaluate {
            val agentConfig = configurations.findByName("appmapAgent")
            val configFile = File(rootProject.projectDir, "appmap.yml")
            // Three failure modes that previously surfaced as "build
            // green, no traces" — flag each one loudly so the operator
            // sees what's wrong instead of a silent miss.
            if (agentConfig == null) {
                logger.warn("[appmap] -Pappmap_enabled=true but the AppMap plugin's appmapAgent " +
                    "configuration is missing -- plugin didn't apply. Check com.appland.appmap " +
                    "plugin marker resolution at /proxy.")
                return@afterEvaluate
            }
            if (!configFile.isFile) {
                logger.warn("[appmap] appmap.yml not found at ${configFile.absolutePath}; " +
                    "agent has no package map and would record nothing useful even if attached.")
                return@afterEvaluate
            }
            val agentJar = runCatching { agentConfig.resolve().firstOrNull() }
                .onFailure { e ->
                    logger.warn("[appmap] couldn't resolve com.appland:appmap-agent " +
                        "from configured repos: ${e.message}. If you're behind a corp mirror, " +
                        "verify it carries Maven Central content for the com.appland group.")
                }
                .getOrNull()
            if (agentJar == null) {
                logger.warn("[appmap] appmapAgent configuration resolved to no jar -- " +
                    "agent attach skipped. Tests will run without AppMap.")
                return@afterEvaluate
            }
            val outputDir = File(projectDir, "tmp/appmap")
            tasks.withType<Test>().configureEach {
                // Build cache invalidates when these change, so a
                // `--rerun-tasks` from the WebUI guarantees a fresh
                // trace dump even if Gradle thinks the test task is
                // up-to-date.
                jvmArgs("-javaagent:${agentJar.absolutePath}")
                systemProperty("appmap.config.file", configFile.absolutePath)
                systemProperty("appmap.output.directory", outputDir.absolutePath)
                // Pin the agent's CWD inference -- on Windows test
                // forks sometimes change cwd before the agent boots
                // and end up writing traces to a path the WebUI
                // doesn't scan.
                systemProperty("appmap.recording.auto", "false")
                // Always create the output dir before tests run; the
                // agent silently no-ops when its target dir doesn't
                // exist on some platforms.
                doFirst {
                    outputDir.mkdirs()
                    logger.lifecycle("[appmap] agent attached for :${project.name}:${this.name} " +
                        "→ output ${outputDir.absolutePath}")
                }
            }
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // JDK 25 — the current LTS. Pinning here also requires the
            // bench-webui-spawned Gradle daemon to run on JDK 25, since
            // daemon-internal Spring Boot tasks (resolveMainClassName,
            // bootRun classpath inspection) load the compiled bytecode
            // and fail with "Unsupported class file major version 69"
            // if the daemon is on an older JVM. JdkDiscovery's
            // bestAvailableHome(matchMajor=25) is responsible for picking
            // the matching install for the daemon JAVA_HOME so the two
            // halves agree.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.14")
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
