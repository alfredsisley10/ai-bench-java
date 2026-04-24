dependencies {
    implementation(project(":harness-core"))
    implementation("org.gradle:gradle-tooling-api:8.10")
}

repositories {
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}
