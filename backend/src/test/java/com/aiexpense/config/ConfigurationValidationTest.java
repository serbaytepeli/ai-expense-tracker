package com.aiexpense.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexpense.auth.internal.JwtProperties;
import com.aiexpense.insight.internal.AiProperties;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * docs/architecture.md section 3.4: invalid configuration must stop the application context.
 * Uses the real application.yml, so the environment variable names (AI_DAILY_LIMIT, ...) are
 * what is tested. No database is needed.
 */
class ConfigurationValidationTest {

    // Fixed, obviously fake test value (base64 of "0123...cdef"); not a real secret.
    private static final String VALID_JWT_SECRET =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiProperties.class, JwtProperties.class})
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("JWT_SECRET=" + VALID_JWT_SECRET);

    @Test
    void defaultsAreValid() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AiProperties ai = context.getBean(AiProperties.class);
            assertThat(ai.dailyLimit()).isEqualTo(5);
            assertThat(ai.reservationTtl()).isEqualTo(Duration.ofMinutes(2));
            assertThat(ai.providerTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(ai.configured()).isFalse();
        });
    }

    @ParameterizedTest(name = "AI_DAILY_LIMIT={0} stops the application")
    @ValueSource(strings = {"0", "-1", "abc", "1.5"})
    void invalidDailyLimitFailsStartup(String value) {
        runner.withPropertyValues("AI_DAILY_LIMIT=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void positiveDailyLimitIsAccepted() {
        runner.withPropertyValues("AI_DAILY_LIMIT=1")
                .run(context -> assertThat(context.getBean(AiProperties.class).dailyLimit()).isEqualTo(1));
    }

    @Test
    void reservationTtlShorterThanTwoProviderTimeoutsFailsStartup() {
        runner.withPropertyValues("AI_PROVIDER_TIMEOUT=20s", "AI_RESERVATION_TTL=40s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void reservationTtlBelowThirtySecondsFailsStartup() {
        runner.withPropertyValues("AI_PROVIDER_TIMEOUT=5s", "AI_RESERVATION_TTL=20s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void apiKeyWithoutModelFailsStartup() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=test-key-not-real", "AI_MODEL=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void apiKeyIsNeverPrintedByToString() {
        runner.withPropertyValues("ANTHROPIC_API_KEY=test-key-not-real", "AI_MODEL=some-model")
                .run(context -> assertThat(context.getBean(AiProperties.class).toString())
                        .doesNotContain("test-key-not-real")
                        .contains("apiKey=***"));
    }

    @Test
    void missingJwtSecretFailsStartup() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shortJwtSecretFailsStartup() {
        runner.withPropertyValues("JWT_SECRET=too-short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void jwtSecretIsNeverPrintedByToString() {
        runner.run(context -> assertThat(context.getBean(JwtProperties.class).toString())
                .doesNotContain(VALID_JWT_SECRET));
    }
}
