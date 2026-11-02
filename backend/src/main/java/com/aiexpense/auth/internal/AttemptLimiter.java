package com.aiexpense.auth.internal;

import com.aiexpense.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window limiter per (action, client IP) (docs/architecture.md section 3.6).
 * Enough for one application instance in the MVP; a shared store is a pre-production task (P-4).
 */
@Component
class AttemptLimiter {

    /** Entries older than their window are dropped when the map grows past this size. */
    static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(Instant start, Duration length, int count) {
        boolean expired(Instant now) {
            return !now.isBefore(start.plus(length));
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    AttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts one attempt and rejects it with 429 RATE_LIMITED (and Retry-After) when the client
     * has used up its attempts in the current window.
     */
    void acquire(String action, String clientIp, AuthProperties.RateLimit limit) {
        Instant now = clock.instant();
        String key = action + "|" + clientIp;
        Window window = windows.compute(key, (k, current) -> current == null || current.expired(now)
                ? new Window(now, limit.window(), 1)
                : new Window(current.start(), current.length(), current.count() + 1));
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
        }
        if (window.count() > limit.maxAttempts()) {
            Duration retryAfter = Duration.between(now, window.start().plus(window.length()));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many attempts. Try again later.", List.of(), retryAfter);
        }
    }
}
