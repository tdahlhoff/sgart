package de.sgart.identity.adapter.in;

import de.sgart.identity.application.InvalidAccountProvisioningException;
import de.sgart.shared.ErrorDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link AccountController}'s application failures to the canonical {@code {code, message,
 * details}} shape (Consistency Conventions) — a {@code 4xx}, never a {@code 500} (AC3). Mirrors
 * {@link DeviceErrorAdvice}.
 */
@RestControllerAdvice
class AccountErrorAdvice {

    @ExceptionHandler(InvalidAccountProvisioningException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidAccountProvisioning(InvalidAccountProvisioningException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }
}
