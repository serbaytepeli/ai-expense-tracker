package com.aiexpense.common;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem details in the project format (docs/api-contract.md section 1):
 * {@code type}, {@code title}, {@code status}, {@code detail}, {@code code}, {@code requestId}
 * and, for validation errors, {@code errors}. Used by the MVC error handler and by the security
 * layer, which answers before requests reach a controller.
 */
public final class ApiProblems {

    public static final String TYPE_BASE = "https://aiexpense.app/problems/";

    private ApiProblems() {
    }

    public static ProblemDetail create(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return decorate(problem, code);
    }

    public static ProblemDetail create(HttpStatusCode status, String code, String detail,
            List<ApiException.FieldError> errors) {
        ProblemDetail problem = create(status, code, detail);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    /** Sets type, code and request ID on an existing problem. */
    public static ProblemDetail decorate(ProblemDetail problem, String code) {
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        RequestIds.current().ifPresent(id -> problem.setProperty("requestId", id));
        return problem;
    }
}
