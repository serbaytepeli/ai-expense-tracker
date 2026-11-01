package com.aiexpense.common.internal;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in log text. This is a safety net: code must still never log passwords,
 * tokens or API keys on purpose (CLAUDE.md rule 3).
 */
public final class LogMasking {

    static final String MASK = "***";

    // key=value, key: value, "key":"value" for sensitive keys (case-insensitive).
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?(?:password|passwd|pwd|secret|jwt[_-]?secret|token|access[_-]?token|refresh[_-]?token"
                    + "|api[_-]?key|x-api-key|authorization)\"?\\s*[:=]\\s*\"?)(?!Bearer\\s)([^\"\\s,;&}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern ANTHROPIC_KEY = Pattern.compile("sk-ant-[A-Za-z0-9_-]+");

    private static final List<Pattern> WHOLE_MATCH_PATTERNS = List.of(JWT, ANTHROPIC_KEY);

    private LogMasking() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = BEARER.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        result = KEY_VALUE.matcher(result).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        for (Pattern pattern : WHOLE_MATCH_PATTERNS) {
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return result;
    }
}
