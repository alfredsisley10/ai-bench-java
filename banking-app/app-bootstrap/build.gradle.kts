plugins {
    id("org.springframework.boot")
}

dependencies {
    // Aggregate every module so the boot jar wires the full monolith.
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":shared-security"))
    implementation(project(":shared-messaging"))
    implementation(project(":ledger-core"))
    implementation(project(":accounts-consumer"))
    implementation(project(":accounts-corporate"))
    implementation(project(":lending-consumer"))
    implementation(project(":lending-corporate"))
    implementation(project(":payments-hub"))
    implementation(project(":cards"))
    implementation(project(":treasury"))
    implementation(project(":fraud-detection"))
    implementation(project(":compliance"))
    implementation(project(":risk-engine"))
    implementation(project(":reg-reporting"))
    implementation(project(":statements"))
    implementation(project(":notifications"))
    implementation(project(":audit-log"))
    implementation(project(":batch-processing"))
    implementation(project(":integration-gateway"))
    implementation(project(":customer-portal-api"))
    implementation(project(":admin-console-api"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation(project(":shared-testing"))
}

// Forward `-PjvmArgs="..."` from the command line onto the bootRun JVM so
// the WebUI's "Start banking app with AppMap agent" button can attach the
// AppMap Java agent without editing this file. The Spring Boot plugin
// does NOT do this automatically — each gradle property the build cares
// about must be wired explicitly.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val extraJvmArgs = providers.gradleProperty("jvmArgs").getOrElse("")
    if (extraJvmArgs.isNotBlank()) {
        jvmArgs(*extraJvmArgs.split(" ").filter { it.isNotBlank() }.toTypedArray())
    }
}
