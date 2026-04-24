dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":ledger-core"))
    implementation(project(":accounts-consumer"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(project(":shared-testing"))
}
