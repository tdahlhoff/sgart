// Story 7.1 (F2): the custom Keycloak Direct-Grant authenticator SPI — verifies a device-signed
// Ed25519 challenge instead of a password (the "browserless sign-in" mechanism locked by the Story
// 7.0 spike). Deliberately its own module: it is packaged as a provider JAR deployed into the
// Keycloak container's own classpath (/opt/keycloak/providers), not into the backend app's
// classpath — the two have different lifecycles and, since Keycloak already provides its own SPI
// jars at runtime, bundling them here would create classloader duplicates.
plugins {
    java
}

group = "de.sgart"
version = "0.0.1-SNAPSHOT"
description = "SGART custom Keycloak Direct-Grant authenticator SPI (Story 7.1)"

repositories {
    mavenCentral()
}

// Version-matched to the Keycloak server image this SPI is deployed into (docker-compose.yml,
// CLAUDE.md §7) — a mismatched SPI version risks binary-incompatible provider interfaces.
val keycloakVersion = "26.7.0"

dependencies {
    // compileOnly: Keycloak's own server runtime already provides these on the classpath the
    // provider JAR is loaded into — bundling them would duplicate classes across classloaders.
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")

    // Matched to the versions the main backend module resolves via the Spring Boot BOM
    // (`./gradlew dependencies`), so both modules run the identical JUnit/AssertJ (CLAUDE.md §7).
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("sgart-keycloak-authenticator")
}

// Deliberately NOT the backend app module's Java 25 toolchain: this provider JAR is loaded by the
// Keycloak server's own JVM (quay.io/keycloak/keycloak:26.7.0 ships JDK 21), not the backend app's
// — a higher class-file version fails to load there ("compiled by a more recent version of the
// Java Runtime", discovered via the Story 7.0 acceptance test). `--release 21` (rather than a
// second JDK 21 toolchain download/install) both targets JDK-21 bytecode and restricts this
// module to JDK-21 APIs, using whichever JDK already runs this Gradle build. Bump only when the
// Keycloak image's bundled JDK does (CLAUDE.md §7 applies to the deploy target, not just the build
// machine).
tasks.withType<JavaCompile> {
    options.release.set(21)
}
