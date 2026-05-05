dependencies {
    implementation(project(":shared-domain"))
    implementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:postgresql")
    implementation("org.testcontainers:junit-jupiter")
    // datafaker 2.4.0: reverted from 2.5.0 (PR #41 bump) after a corp
    // Artifactory's maven-external-virtual returned 404 on 2.5.0 +
    // its transitive libphonenumber:9.0.14. Operators on mirrors that
    // CAN serve newer datafaker can run /mirror's 🔬 Probe versions on
    // this coord to see what their mirror carries and bump locally.
    implementation("net.datafaker:datafaker:2.4.0")
}
