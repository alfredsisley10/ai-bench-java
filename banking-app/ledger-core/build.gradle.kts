dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":shared-messaging"))
    implementation(project(":shared-security"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.github.ben-manes.caffeine:caffeine")

    testImplementation(project(":shared-testing"))
}
