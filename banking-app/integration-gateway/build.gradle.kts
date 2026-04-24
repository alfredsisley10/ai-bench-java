dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-messaging"))
    implementation(project(":payments-hub"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    testImplementation(project(":shared-testing"))
}
