package com.bunshock.note_app_for_it.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Renders every deliberate failure as backend-contract.md §11's one envelope. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorBody(ErrorPayload error) {
    }

    public record ErrorPayload(String code, String message, Map<String, Object> details) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorBody(new ErrorPayload(ex.getCode(), ex.getMessage(), ex.getDetails())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorBody(new ErrorPayload("VALIDATION_FAILED", "Solicitud inválida.", fieldErrors)));
    }

    // Deliberately no catch-all here: an unexpected exception (SQL error, NPE)
    // stays a plain 500 with Spring Boot's default body, so it's never
    // mistaken in logs/monitoring for a deliberate ApiException rejection —
    // matches this app's "fail loud, fail structured" principle (§0 #6) by
    // NOT structuring the one class of failure that was never anticipated.
}
