package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.InvalidHouseholdNameException;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.ErrorDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps write-side domain/application failures to the canonical {@code {code, message, details}}
 * shape (Consistency Conventions) — the full error-mapping surface Story 1.4 wired minimally for
 * {@code /me}. The client already localizes by {@code code} (Story 1.3).
 */
@RestControllerAdvice
class WriteErrorAdvice {

    @ExceptionHandler(InvalidHouseholdNameException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidHouseholdName(InvalidHouseholdNameException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidCommandEnvelopeException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidCommandEnvelope(InvalidCommandEnvelopeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    ResponseEntity<ErrorDescriptor> handleConcurrencyConflict(ConcurrencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(NotAMemberException.class)
    ResponseEntity<ErrorDescriptor> handleNotAMember(NotAMemberException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.errorDescriptor());
    }
}
