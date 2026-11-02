package com.aiexpense.common.internal;

import com.aiexpense.common.ApiException;
import com.aiexpense.common.ApiProblems;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every error handled by Spring MVC into an RFC 9457 {@code application/problem+json}
 * response with the fields from docs/api-contract.md: {@code type}, {@code title},
 * {@code status}, {@code detail}, {@code code}, {@code requestId} (and {@code errors} for
 * validation failures).
 */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = ApiProblems.TYPE_BASE;
    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String VALIDATION_DETAIL = "Request contains invalid fields";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    /** Expected errors with a documented code (EMAIL_TAKEN, TOKEN_EXPIRED, ...). */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        ProblemDetail body = ApiProblems.create(ex.status(), ex.code(), ex.getMessage(), ex.fieldErrors());
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfter().toSeconds())));
        }
        return super.createResponseEntity(body, headers, ex.status(), request);
    }

    /** Unexpected errors: logged with the stack trace, answered without any internal details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
        return createResponseEntity(body, new HttpHeaders(), status, request);
    }

    /** Bean Validation failures on request bodies: 400 VALIDATION_ERROR with one entry per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiException.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiException.FieldError(error.getField(), error.getDefaultMessage()))
                .sorted((a, b) -> a.field().compareTo(b.field()))
                .toList();
        ProblemDetail body = ApiProblems.create(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, VALIDATION_DETAIL, errors);
        return super.createResponseEntity(body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Missing or malformed JSON body. The parser message is not returned (it can echo input). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ApiProblems.create(HttpStatus.BAD_REQUEST, VALIDATION_ERROR,
                "Request body is missing or is not valid JSON");
        return super.createResponseEntity(body, headers, HttpStatus.BAD_REQUEST, request);
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
        // getProperties() is null until a property has been set.
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            ApiProblems.decorate(problem, codeFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /**
     * Code for errors without a documented code; derived from the HTTP status
     * (docs/api-contract.md section 1).
     */
    static String codeFor(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return "INTERNAL_ERROR";
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }
}
