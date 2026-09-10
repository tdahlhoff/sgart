package de.sgart.identity.adapter.in;

import de.sgart.identity.application.InvalidDeviceRegistrationException;
import de.sgart.shared.ErrorDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link DeviceController}'s application failures to the canonical {@code {code, message,
 * details}} shape (Consistency Conventions), mirroring {@code WriteErrorAdvice} in {@code
 * collaboration.adapter.in}.
 */
@RestControllerAdvice
class DeviceErrorAdvice {

    @ExceptionHandler(InvalidDeviceRegistrationException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidDeviceRegistration(InvalidDeviceRegistrationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }
}
