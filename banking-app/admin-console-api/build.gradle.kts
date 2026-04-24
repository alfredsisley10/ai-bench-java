dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-security"))
    implementation(project(":ledger-core"))
    implementation(project(":accounts-consumer"))
    implementation(project(":accounts-corporate"))
    implementation(project(":lending-corporate"))
    implementation(project(":payments-hub"))
    implementation(project(":compliance"))
    implementation(project(":fraud-detection"))
    implementation(project(":audit-log"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation(project(":shared-testing"))
}
