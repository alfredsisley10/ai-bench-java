dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":ledger-core"))
    implementation(project(":accounts-consumer"))
    implementation(project(":accounts-corporate"))
    implementation(project(":lending-corporate"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(project(":shared-testing"))
}
