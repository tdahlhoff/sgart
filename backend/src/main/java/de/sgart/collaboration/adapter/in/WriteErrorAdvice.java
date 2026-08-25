package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.exception.DuplicateStoreNameApplicationException;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidHouseholdNameException;
import de.sgart.collaboration.application.exception.InvalidStoreNameException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.application.exception.RenameNotPermittedApplicationException;
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

    @ExceptionHandler(InvalidStoreNameException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidStoreName(InvalidStoreNameException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidCommandEnvelopeException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidCommandEnvelope(InvalidCommandEnvelopeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(DuplicateStoreNameApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleDuplicateStoreName(DuplicateStoreNameApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    ResponseEntity<ErrorDescriptor> handleConcurrencyConflict(ConcurrencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(NotAMemberException.class)
    ResponseEntity<ErrorDescriptor> handleNotAMember(NotAMemberException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.errorDescriptor());
    }

    @ExceptionHandler(NotAHouseholdMemberApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleNotAHouseholdMember(NotAHouseholdMemberApplicationException exception) {
        // Defense-in-depth: the aggregate's own membership guard (reachable only under an
        // ACL/event-stream divergence) surfaces as a proper 403 with the same client code as the
        // ACL's NotAMemberException — never a 500 for an unmapped failure. The store handlers
        // translate the domain guard into this application exception so adapter.in never reaches
        // into the domain layer (AD-1).
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.errorDescriptor());
    }

    @ExceptionHandler(RenameNotPermittedApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleRenameNotPermitted(RenameNotPermittedApplicationException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.errorDescriptor());
    }
}
