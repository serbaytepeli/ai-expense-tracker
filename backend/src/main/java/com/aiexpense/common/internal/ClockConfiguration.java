package com.aiexpense.common.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of the current time. Code must inject {@link Clock} instead of calling
 * {@code LocalDate.now()} and similar methods (enforced by ArchUnit), so tests can fix the time.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
