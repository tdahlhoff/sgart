rootProject.name = "sgart-backend"

// Story 7.1 (F2): the custom Keycloak Direct-Grant authenticator SPI. Its own Gradle module —
// a separate provider JAR deployed into the Keycloak container's classpath, never a dependency of
// the backend app module (different classpath and deploy target) — but still built and tested by
// `./gradlew test` from this root, so CI covers it in one green build.
include(":keycloak-authenticator")
