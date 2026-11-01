package com.aiexpense.common;

import java.util.Optional;
import org.slf4j.MDC;

/** Request ID names shared by the filter, logging and error responses. */
public final class RequestIds {

    /** HTTP header carrying the request ID in both directions. */
    public static final String HEADER = "X-Request-Id";

    /** MDC key, so every log line written while handling a request includes the ID. */
    public static final String MDC_KEY = "requestId";

    private RequestIds() {
    }

    /** The request ID of the request being handled on this thread, if any. */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
