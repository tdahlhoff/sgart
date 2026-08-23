package de.sgart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression guard for the doubled eager-boot trap (Story 1.6 Dev Notes): the KurrentDB client
 * bean must never connect at context startup — gRPC channels connect lazily on first RPC. Points
 * the connection string at a deliberately unreachable host — mirrors {@code
 * ContextLoadsWithoutKeycloakTest} (Story 1.4) and {@code ContextLoadsWithoutPostgresTest}.
 */
@SpringBootTest
class ContextLoadsWithoutKurrentDbTest {

    @DynamicPropertySource
    static void unreachableKurrentDb(DynamicPropertyRegistry registry) {
        registry.add("sgart.kurrentdb.connection-string", () -> "esdb://127.0.0.1:1?tls=false");
    }

    @Test
    void contextLoadsEvenThoughKurrentDbIsUnreachable() {
        // Fails the build if the client bean ever regresses to an eager connection attempt.
    }
}
