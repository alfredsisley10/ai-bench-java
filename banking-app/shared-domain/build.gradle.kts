dependencies {
    // Pure domain — no Spring, no JPA. Enterprise apps often have a pure-Java
    // domain core so value objects can be shared without framework weight.
    implementation("org.jspecify:jspecify:1.0.0")
}
