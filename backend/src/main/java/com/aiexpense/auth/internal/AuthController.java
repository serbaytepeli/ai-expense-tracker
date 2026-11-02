package com.aiexpense.auth.internal;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/api-contract.md section 2. */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
class AuthController {

    private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    record RegisterRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 60) String displayName,
            @Size(max = 64) String timeZone) {
        @Override
        public String toString() {
            return "RegisterRequest[password=***]";
        }
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 1024) String password) {
        @Override
        public String toString() {
            return "LoginRequest[password=***]";
        }
    }

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";
        }
    }

    // OpenAPI only: these fields are always present, so the generated client types must not be optional.
    record UserResponse(@Schema(requiredMode = REQUIRED) UUID id, @Schema(requiredMode = REQUIRED) String email, @Schema(requiredMode = REQUIRED) String displayName,
            @Schema(requiredMode = REQUIRED) String currency, @Schema(requiredMode = REQUIRED) String timeZone, @Schema(requiredMode = REQUIRED) String locale) {
    }

    record AuthResponse(@Schema(requiredMode = REQUIRED) String accessToken, @Schema(requiredMode = REQUIRED) String refreshToken,
            @Schema(requiredMode = REQUIRED) long expiresIn, @Schema(requiredMode = REQUIRED) UserResponse user) {
        @Override
        public String toString() {
            return "AuthResponse[tokens=***, user=" + user.id() + "]";
        }
    }

    record TokenResponse(@Schema(requiredMode = REQUIRED) String accessToken, @Schema(requiredMode = REQUIRED) String refreshToken,
            @Schema(requiredMode = REQUIRED) long expiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[tokens=***]";
        }
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Registered; a new session starts")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "EMAIL_TAKEN", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    AuthResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        return toResponse(auth.register(body.email(), body.password(), body.displayName(), body.timeZone(),
                request.getRemoteAddr()));
    }

    @PostMapping("/login")
    @ApiResponse(responseCode = "200", description = "Logged in; a new session starts")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return toResponse(auth.login(body.email(), body.password(), request.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    @ApiResponse(responseCode = "200", description = "New token pair in the same session")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401",
            description = "UNAUTHORIZED, TOKEN_EXPIRED, SESSION_REVOKED, TOKEN_ALREADY_ROTATED or TOKEN_REUSED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    TokenResponse refresh(@Valid @RequestBody RefreshRequest body) {
        AuthService.RotatedTokens result = auth.refresh(body.refreshToken());
        return new TokenResponse(result.access().token(), result.refreshToken(), result.access().expiresInSeconds());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "Session revoked")
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED, TOKEN_EXPIRED or SESSION_REVOKED", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    void logout(@AuthenticationPrincipal Jwt jwt) {
        auth.logout(UUID.fromString(jwt.getClaimAsString(AccessTokens.SESSION_CLAIM)));
    }

    private static AuthResponse toResponse(AuthService.SessionTokens session) {
        var user = session.user();
        return new AuthResponse(session.access().token(), session.refreshToken(), session.access().expiresInSeconds(),
                new UserResponse(user.id(), user.email(), user.displayName(), user.currency(), user.timeZone(),
                        user.locale()));
    }
}
