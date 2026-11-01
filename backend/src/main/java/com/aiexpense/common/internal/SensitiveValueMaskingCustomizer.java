package com.aiexpense.common.internal;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link LogMasking} to every string value of every JSON log line (message, MDC values,
 * stack traces). Registered with {@code logging.structured.json.customizer} in application.yml.
 */
public class SensitiveValueMaskingCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasking::mask));
    }
}
