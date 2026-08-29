package de.sgart.collaboration.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.exception.TripNotActiveApplicationException;
import de.sgart.shared.ErrorDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Direct mapping-contract coverage for {@link WriteErrorAdvice} entries whose domain guard is not yet
 * reachable through the live endpoint. {@link TripNotActiveApplicationException} is thrown only for a
 * {@code DONE} trip — a Story 3.4 state with no completion event in Story 3.2 — so a full MockMvc
 * round-trip cannot exercise it here (its sibling 400/403/404/201 add-store paths are covered in
 * {@code TripControllerTest}). Asserting the advice still maps it to the canonical {@code 409 /
 * trip.notActive} contract the client localizes by code keeps the Cl. 9 Action 2 promise ("an
 * error-advice contract test for every new endpoint") honest for the defensive guard too.
 */
class WriteErrorAdviceTest {

    @Test
    void mapsTripNotActiveToConflictWithTheStableCode() {
        ResponseEntity<ErrorDescriptor> response =
                new WriteErrorAdvice().handleTripNotActive(new TripNotActiveApplicationException("trip is DONE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("trip.notActive");
    }
}
