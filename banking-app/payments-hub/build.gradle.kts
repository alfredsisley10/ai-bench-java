dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":shared-messaging"))
    implementation(project(":ledger-core"))
    implementation(project(":accounts-consumer"))
    implementation(project(":accounts-corporate"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-activemq")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(project(":shared-testing"))
}
