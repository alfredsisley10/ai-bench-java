dependencies {
    implementation(project(":shared-domain"))
    implementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:postgresql")
    implementation("org.testcontainers:junit-jupiter")
    implementation("net.datafaker:datafaker:2.4.0")
}
