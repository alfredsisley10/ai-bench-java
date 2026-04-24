dependencies {
    implementation(project(":shared-domain"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.hibernate.orm:hibernate-envers")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
}
