package com.aiexpense.insight.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI settings read from AI_* / ANTHROPIC_API_KEY environment variables
 * (docs/architecture.md section 3.4). Invalid values stop the application from starting.
 *
 * @param apiKey Anthropic API key; may be empty, then the AI endpoint answers 503
 * @param model model name; required when {@code apiKey} is set (never hard-coded)
 * @param dailyLimit successful AI insights per user per day; must be positive
 * @param reservationTtl how long a quota reservation stays valid
 * @param providerTimeout timeout for one AI provider call
 */
@Validated
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        String apiKey,
        String model,
        @Positive int dailyLimit,
        @NotNull Duration reservationTtl,
        @NotNull Duration providerTimeout) {

    private static final Duration MIN_RESERVATION_TTL = Duration.ofSeconds(30);

    /** True when an API key is configured. */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @AssertTrue(message = "AI_MODEL must be set when ANTHROPIC_API_KEY is set")
    public boolean isModelSetWhenConfigured() {
        return !configured() || (model != null && !model.isBlank());
    }

    @AssertTrue(message = "AI_RESERVATION_TTL must be >= 30s and >= 2 x AI_PROVIDER_TIMEOUT + 5s")
    public boolean isReservationTtlLongEnough() {
        if (reservationTtl == null || providerTimeout == null) {
            return true; // reported by @NotNull
        }
        Duration minimumForRetries = providerTimeout.multipliedBy(2).plusSeconds(5);
        return reservationTtl.compareTo(MIN_RESERVATION_TTL) >= 0
                && reservationTtl.compareTo(minimumForRetries) >= 0;
    }

    /** Never print the API key (records include every component in toString by default). */
    @Override
    public String toString() {
        return "AiProperties[apiKey=" + (configured() ? "***" : "<empty>")
                + ", model=" + model
                + ", dailyLimit=" + dailyLimit
                + ", reservationTtl=" + reservationTtl
                + ", providerTimeout=" + providerTimeout + "]";
    }
}
