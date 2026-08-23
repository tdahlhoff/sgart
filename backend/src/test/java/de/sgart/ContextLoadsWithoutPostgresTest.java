package de.sgart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression guard for the doubled eager-boot trap (Story 1.6 Dev Notes): the PostgreSQL {@code
 * DataSource} bean must never connect at context startup, and Flyway (which validates and
 * connects eagerly) must stay off by default. Points the datasource at a deliberately unreachable
 * host — mirrors {@code ContextLoadsWithoutKeycloakTest} (Story 1.4).
 */
@SpringBootTest
class ContextLoadsWithoutPostgresTest {

    @DynamicPropertySource
    static void unreachableDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:1/unreachable");
    }

    @Test
    void contextLoadsEvenThoughPostgresIsUnreachable() {
        // Fails the build if datasource/Flyway wiring ever regresses to an eager connection.
    }
}
