package de.sgart;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for the health-check endpoint that local smoke tests and future deploy
 * automation (scripts/health-check.sh, docs/first-real-world-test.md) rely on to prove the
 * backend actually started, not just that the JVM is running. The datasource health indicator is
 * disabled here (unlike a real run) so this stays a pure unit-level context test with no live
 * Postgres, matching {@code ContextLoadsWithoutPostgresTest} — the DB-down case is that test's job.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "management.health.db.enabled=false")
class ActuatorHealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
