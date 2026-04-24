package com.omnibank.shared.testing;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Canonical Postgres Testcontainers setup for integration tests. All modules
 * that need a real DB pull this from shared-testing rather than standing up
 * their own — keeps version drift from becoming a class of bugs.
 */
public final class OmnibankTestContainers {

    private static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16-alpine");

    private OmnibankTestContainers() {}

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(POSTGRES)
                .withDatabaseName("omnibank_test")
                .withUsername("omnibank")
                .withPassword("omnibank")
                .withReuse(true);
    }
}
