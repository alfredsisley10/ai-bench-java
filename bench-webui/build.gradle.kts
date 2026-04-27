plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
    // Required for @Serializable to actually generate a serializer.
    // Without the COMPILER plugin, the runtime kotlinx-serialization-json
    // dependency is just bytes -- the @Serializable annotation is a
    // no-op and SerializationException("Serializer for class 'X' is
    // not found") fires at the first decodeFromString call.
    kotlin("plugin.serialization") version "2.0.20"
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.appland.appmap") version "1.2.0"
}

group = "com.aibench"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Springdoc auto-generates an OpenAPI 3 spec from the @GetMapping /
    // @PostMapping annotations on bench-webui controllers and serves
    // both /v3/api-docs and a Swagger UI at /swagger-ui/index.html.
    // The "Demo API" page embeds that UI in an iframe; no separate
    // hand-maintained API listing to drift from the code.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Gradle 9 enforces an explicit JUnit Platform launcher on the test
    // runtime classpath; spring-boot-starter-test only ships the API.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

appmap {
    configFile.set(layout.projectDirectory.file("appmap.yml"))
    outputDirectory.set(layout.buildDirectory.dir("appmap"))
}

tasks.test {
    useJUnitPlatform()
}
