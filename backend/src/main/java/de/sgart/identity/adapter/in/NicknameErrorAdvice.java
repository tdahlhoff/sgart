package de.sgart.identity.adapter.in;

import de.sgart.identity.application.InvalidMembershipNicknameException;
import de.sgart.shared.ErrorDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link NicknameController}'s application failures to the canonical {@code {code, message,
 * details}} shape (Consistency Conventions), mirroring {@code DeviceErrorAdvice}. {@code
 * NotAMemberException} is already mapped globally (see {@code WriteErrorAdvice} in {@code
 * collaboration.adapter.in}) — no duplicate handler needed here.
 */
@RestControllerAdvice
class NicknameErrorAdvice {

    @ExceptionHandler(InvalidMembershipNicknameException.class)
    ResponseEntity<ErrorDescriptor> handleInvalidMembershipNickname(InvalidMembershipNicknameException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.errorDescriptor());
    }
}
