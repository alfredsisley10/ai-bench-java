dependencies {
    implementation(project(":shared-domain"))
    implementation("org.springframework.boot:spring-boot-starter-integration")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-activemq")
    implementation("org.springframework.integration:spring-integration-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
