dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":shared-messaging"))
    implementation(project(":ledger-core"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(project(":shared-testing"))
}
