// TASK: PHASE-2 (atoms 01-13)
package com.scheduler.api.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Base class for all domain errors surfaced through the REST layer.
 * Carries the HTTP status and the machine-readable {@code code} from
 * API-SPEC section 9; rendered as RFC-7807 by {@link GlobalExceptionHandler}.
 * Messages must never leak other-tenant identifiers (SECURITY-SPEC 3.3).
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;
    private final List<String> errors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ApiException(HttpStatus status, String code, String message, String field) {
        this(status, code, message, field, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        String field, List<String> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.errors = errors;
    }

    // --- Convenience factories for the recurring API-SPEC error shapes ---

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static ApiException badRequest(String code, String message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, field);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
