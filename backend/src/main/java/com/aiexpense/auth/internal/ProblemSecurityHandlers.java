package com.aiexpense.auth.internal;

import com.aiexpense.common.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401/403 responses from the security filter chain in the project's problem format, with
 * TOKEN_EXPIRED / SESSION_REVOKED / UNAUTHORIZED codes (docs/api-contract.md section 1).
 * The token itself is never echoed.
 */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    ProblemSecurityHandlers(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = switch (validationErrorCode(exception)) {
            case SecurityConfiguration.ERROR_TOKEN_EXPIRED -> "TOKEN_EXPIRED";
            case SecurityConfiguration.ERROR_SESSION_REVOKED -> "SESSION_REVOKED";
            default -> "UNAUTHORIZED";
        };
        String detail = switch (code) {
            case "TOKEN_EXPIRED" -> "Access token has expired";
            case "SESSION_REVOKED" -> "Session has been revoked";
            default -> "Authentication is required";
        };
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, code, detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ApiProblems.create(status, code, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }

    /** The first validator error code found in the cause chain, or "" if none. */
    private static String validationErrorCode(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException validation) {
                return validation.getErrors().stream().map(OAuth2Error::getErrorCode).findFirst().orElse("");
            }
        }
        return "";
    }
}
