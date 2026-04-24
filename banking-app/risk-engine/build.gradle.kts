dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-persistence"))
    implementation(project(":shared-messaging"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(project(":shared-testing"))
}
