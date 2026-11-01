package com.aiexpense.auth.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing settings read from the JWT_SECRET environment variable
 * (docs/architecture.md section 3.4). Token handling itself is implemented in M1 (T1.2).
 *
 * @param secret HMAC key; at least 32 bytes (256 bits)
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank(message = "JWT_SECRET must be set") String secret) {

    static final int MIN_SECRET_BYTES = 32;

    @AssertTrue(message = "JWT_SECRET must be at least 32 bytes (256 bits)")
    public boolean isSecretLongEnough() {
        return secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }

    /** Never print the secret. */
    @Override
    public String toString() {
        return "JwtProperties[secret=***]";
    }
}
