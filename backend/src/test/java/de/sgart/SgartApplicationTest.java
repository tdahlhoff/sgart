package de.sgart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the Spring application context wires up cleanly. Uses the default mock web environment
 * so it needs no external infrastructure (Keycloak, KurrentDB, PostgreSQL are for later stories).
 */
@SpringBootTest
class SgartApplicationTest {

    @Test
    void contextLoads() {
        // Fails the build if component wiring or configuration is broken.
    }
}
