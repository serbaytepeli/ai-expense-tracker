package com.aiexpense.auth.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token and rate limit settings (docs/architecture.md section 3.6). Defaults in application.yml.
 *
 * @param issuer value of the JWT {@code iss} claim
 * @param accessTokenTtl access token lifetime (15 minutes)
 * @param refreshTokenTtl refresh token lifetime (30 days)
 * @param reuseGracePeriod reuse of a rotated refresh token within this period is treated as a
 *     race (TOKEN_ALREADY_ROTATED, session stays open); later reuse revokes the session
 * @param loginRateLimit login attempts per client IP
 * @param registerRateLimit registrations per client IP
 */
@Validated
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration reuseGracePeriod,
        @Valid @NotNull RateLimit loginRateLimit,
        @Valid @NotNull RateLimit registerRateLimit) {

    /** At most {@code maxAttempts} requests per {@code window} for one client IP. */
    public record RateLimit(@Min(1) int maxAttempts, @NotNull Duration window) {
    }
}
