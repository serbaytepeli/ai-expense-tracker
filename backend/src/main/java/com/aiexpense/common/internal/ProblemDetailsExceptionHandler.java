package com.aiexpense.common.internal;

import com.aiexpense.common.RequestIds;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error handled by Spring MVC into an RFC 9457 {@code application/problem+json}
 * response with the fields from docs/api-contract.md: {@code type}, {@code title},
 * {@code status}, {@code detail}, {@code code} and {@code requestId}.
 */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = "https://aiexpense.app/problems/";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    /** Unexpected errors: logged with the stack trace, answered without any internal details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
        return createResponseEntity(body, new HttpHeaders(), status, request);
    }

    /** Unknown paths: Spring's default detail mentions static resource handling; keep it generic. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "The requested resource was not found.");
        return createResponseEntity(body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            enrich(problem, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static void enrich(ProblemDetail problem, HttpStatusCode statusCode) {
        String code = codeFor(statusCode);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        RequestIds.current().ifPresent(id -> problem.setProperty("requestId", id));
    }

    /**
     * Machine-readable error code. Feature modules will add their own codes (VALIDATION_ERROR,
     * EMAIL_TAKEN, ...); until then the code is derived from the HTTP status.
     */
    static String codeFor(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return "INTERNAL_ERROR";
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }
}
