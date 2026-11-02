package com.aiexpense.user;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * E-mail rules of docs/database-design.md section 3. Registration and login use the same
 * normalization, so a user can sign in with any capitalisation or surrounding spaces.
 */
public final class EmailAddresses {

    public static final int MAX_LENGTH = 254;

    // Deliberately simple: something@something, no whitespace. No provider-specific rewriting.
    private static final Pattern SIMPLE_FORMAT = Pattern.compile("[^@\\s]+@[^@\\s]+");

    private EmailAddresses() {
    }

    /**
     * Trims and lower-cases with {@link Locale#ROOT}. ROOT is required: with a Turkish default
     * locale, {@code "I".toLowerCase()} is {@code "ı"} and the same address would be stored twice.
     */
    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Checks an already normalized address. */
    public static boolean isValid(String normalizedEmail) {
        return normalizedEmail != null
                && !normalizedEmail.isEmpty()
                && normalizedEmail.length() <= MAX_LENGTH
                && SIMPLE_FORMAT.matcher(normalizedEmail).matches();
    }
}
