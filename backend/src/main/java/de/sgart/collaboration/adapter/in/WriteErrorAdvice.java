package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.exception.DuplicateItemApplicationException;
import de.sgart.collaboration.application.exception.DuplicateStoreNameApplicationException;
import de.sgart.collaboration.application.exception.InvalidCommandEnvelopeException;
import de.sgart.collaboration.application.exception.InvalidHouseholdNameException;
import de.sgart.collaboration.application.exception.InvalidItemNameException;
import de.sgart.collaboration.application.exception.InvalidItemNoteException;
import de.sgart.collaboration.application.exception.InvalidItemQuantityException;
import de.sgart.collaboration.application.exception.InvalidMoveTargetException;
import de.sgart.collaboration.application.exception.InvalidShoppingListNameException;
import de.sgart.collaboration.application.exception.InvalidStoreNameException;
import de.sgart.collaboration.application.exception.InvalidTripStoreSelectionException;
import de.sgart.collaboration.application.exception.ItemChangeNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.ItemNotFoundApplicationException;
import de.sgart.collaboration.application.exception.ItemNotDuringTripApplicationException;
import de.sgart.collaboration.application.exception.ItemTransferInProgressApplicationException;
import de.sgart.collaboration.application.exception.ListNameChangeNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.MoveTargetNotOpenException;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.application.exception.RenameNotPermittedApplicationException;
import de.sgart.collaboration.application.exception.ShoppingListNotFoundException;
import de.sgart.collaboration.application.exception.TripNotActiveApplicationException;
import de.sgart.collaboration.application.exception.TripNotFoundException;
import de.sgart.collaboration.application.exception.TripNotCompletableApplicationException;
import de.sgart.collaboration.application.exception.TripNotStartableApplicationException;
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

    @ExceptionHandler(InvalidShoppingListNameException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidShoppingListName(InvalidShoppingListNameException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ListNameChangeNotPermittedApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleListNameChangeNotPermitted(
            ListNameChangeNotPermittedApplicationException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ShoppingListNotFoundException.class)
    ResponseEntity<ErrorDescriptor> handleShoppingListNotFound(ShoppingListNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidItemNameException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidItemName(InvalidItemNameException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidItemNoteException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidItemNote(InvalidItemNoteException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidItemQuantityException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidItemQuantity(InvalidItemQuantityException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(DuplicateItemApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleDuplicateItem(DuplicateItemApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ItemNotFoundApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleItemNotFound(ItemNotFoundApplicationException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ItemChangeNotPermittedApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleItemChangeNotPermitted(ItemChangeNotPermittedApplicationException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(exception.errorDescriptor());
    }

    @ExceptionHandler(MoveTargetNotOpenException.class)
    ResponseEntity<ErrorDescriptor> handleMoveTargetNotOpen(MoveTargetNotOpenException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidMoveTargetException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidMoveTarget(InvalidMoveTargetException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(InvalidTripStoreSelectionException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidTripStoreSelection(InvalidTripStoreSelectionException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }

    @ExceptionHandler(TripNotStartableApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleTripNotStartable(TripNotStartableApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(TripNotCompletableApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleTripNotCompletable(TripNotCompletableApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ItemNotDuringTripApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleItemNotDuringTrip(ItemNotDuringTripApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(ItemTransferInProgressApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleItemTransferInProgress(ItemTransferInProgressApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }

    @ExceptionHandler(TripNotFoundException.class)
    ResponseEntity<ErrorDescriptor> handleTripNotFound(TripNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.errorDescriptor());
    }

    @ExceptionHandler(TripNotActiveApplicationException.class)
    ResponseEntity<ErrorDescriptor> handleTripNotActive(TripNotActiveApplicationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(exception.errorDescriptor());
    }
}
