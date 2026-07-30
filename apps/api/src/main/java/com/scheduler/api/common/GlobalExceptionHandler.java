// TASK: P1-T05 / P1-T09
package com.scheduler.api.common;

import com.scheduler.api.auth.otp.OtpRateLimitException;
import com.scheduler.api.tenant.TenantNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 problem-details mapping. Messages never expose other-tenant
 * entity IDs or internals (SECURITY-SPEC 3.3).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setProperty("code", "INVALID_IDENTIFIER");
        FieldError first = ex.getBindingResult().getFieldError();
        if (first != null) {
            pd.setDetail(first.getField() + ": " + first.getDefaultMessage());
            pd.setProperty("field", first.getField());
        }
        return pd;
    }

    @ExceptionHandler(OtpRateLimitException.class)
    public ProblemDetail handleRateLimit(OtpRateLimitException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        pd.setTitle("Too many requests");
        pd.setDetail("OTP request limit reached. Try again later.");
        pd.setProperty("code", "OTP_RATE_LIMIT_EXCEEDED");
        return pd;
    }

    @ExceptionHandler(TenantNotFoundException.class)
    public ProblemDetail handleTenantNotFound(TenantNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Tenant not found");
        pd.setDetail(ex.getMessage());
        pd.setProperty("code", "TENANT_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Forbidden");
        pd.setDetail("Tenant mismatch or insufficient role.");
        pd.setProperty("code", "TENANT_MISMATCH");
        return pd;
    }

    /**
     * Generic mapping for the Phase-2 domain exceptions (locations, resources,
     * service types, holidays, slots, bookings). Each carries its own HTTP
     * status + machine-readable code per API-SPEC section 9.
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(ex.getStatus());
        pd.setTitle(ex.getStatus().getReasonPhrase());
        pd.setDetail(ex.getMessage());
        pd.setProperty("code", ex.getCode());
        if (ex.getField() != null) {
            pd.setProperty("field", ex.getField());
        }
        if (ex.getErrors() != null && !ex.getErrors().isEmpty()) {
            pd.setProperty("errors", ex.getErrors());
        }
        return pd;
    }

    /**
     * Serialization failures / lock-acquisition failures raised by the
     * pessimistic booking guard (ADR-002) surface as 409 SLOT_UNAVAILABLE —
     * the losing transaction of a slot race must never yield a 500.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.debug("Concurrency failure mapped to SLOT_UNAVAILABLE: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("The requested slot was taken by a concurrent request.");
        pd.setProperty("code", "SLOT_UNAVAILABLE");
        return pd;
    }

    /** DB unique-constraint backstop (e.g. duplicate holiday date). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
        log.debug("Data integrity violation: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("The request conflicts with existing data.");
        pd.setProperty("code", "CONFLICT");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        pd.setDetail("An unexpected error occurred.");
        pd.setProperty("code", "INTERNAL_ERROR");
        return pd;
    }
}
