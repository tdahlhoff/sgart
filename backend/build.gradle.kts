plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "de.sgart"
version = "0.0.1-SNAPSHOT"
description = "SGART modular monolith — backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web/transport lives only in adapter.in — the domain never sees these types (AD-1),
    // enforced by the ArchUnit architecture test.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // JWT resource-server validation for the identity context's adapter.in (Story 1.4, AD-5).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // PostgreSQL read side (Story 1.6): durable Identity ACL mapping + household read model.
    // Plain SQL + JdbcClient, not JPA — read models are simple projections (Clarification 3).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Boot 4 split Flyway's Spring integration (FlywayAutoConfiguration, the `spring.flyway.*`
    // properties) out of spring-boot-jdbc into its own module — without it, `flyway-core` sits on
    // the classpath but no migration ever runs (discovered via the Epic-1-retro-mandated real
    // end-to-end stack run, Story 2.1: SGART_FLYWAY_ENABLED=true silently did nothing).
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Real KurrentDB EventStore adapter (Story 1.6, deferred from 1.5).
    implementation("io.kurrent:kurrentdb-client:1.2.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    // Testcontainers own their container lifecycle in the adapter/projector contract tests — the
    // dev compose containers are never used from tests (Story 1.4's Keycloak-Testcontainers
    // precedent).
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
