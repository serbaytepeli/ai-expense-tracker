package com.aiexpense.common;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * An expected error with a documented API code (docs/api-contract.md section 1). Turned into an
 * RFC 9457 problem response by the common error handler. The {@code detail} is shown to clients,
 * so it must never contain secrets, stack traces or data of another user.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One invalid request field. */
    public record FieldError(String field, String message) {
    }

    private final HttpStatus status;
    private final String code;
    private final transient List<FieldError> fieldErrors;
    private final transient Duration retryAfter;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of(), null);
    }

    public ApiException(HttpStatus status, String code, String detail, List<FieldError> fieldErrors, Duration retryAfter) {
        super(detail);
        this.status = Objects.requireNonNull(status);
        this.code = Objects.requireNonNull(code);
        this.fieldErrors = List.copyOf(fieldErrors);
        this.retryAfter = retryAfter;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request contains invalid fields",
                List.of(new FieldError(field, message)), null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    /** Value for the Retry-After header, if any. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
