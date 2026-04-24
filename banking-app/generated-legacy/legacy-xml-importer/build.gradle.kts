dependencies {
    implementation("org.slf4j:slf4j-api")
}

// Retired module — only present so that `git blame`-style
// archaeology, rare bug archeology, and dependency graph
// analysis still work. No tests, no runtime usage.
tasks.withType<Test> { enabled = false }
