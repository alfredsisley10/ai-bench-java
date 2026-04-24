dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-security"))
    implementation(project(":accounts-consumer"))
    implementation(project(":lending-consumer"))
    implementation(project(":cards"))
    implementation(project(":payments-hub"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation(project(":shared-testing"))
}
