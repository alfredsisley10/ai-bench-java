dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-messaging"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-activemq")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(project(":shared-testing"))
}
