package com.aiexpense.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** docs/database-design.md section 3. */
class EmailAddressesTest {

    @Test
    void trimsAndLowerCases() {
        assertThat(EmailAddresses.normalize("  Ali@Example.COM \t")).isEqualTo("ali@example.com");
    }

    @Test
    void turkishDefaultLocaleDoesNotChangeTheResult() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // With tr-TR, "I".toLowerCase() would be the dotless "ı"; Locale.ROOT gives "i".
            assertThat("ISIL".toLowerCase()).isEqualTo("ısıl");
            assertThat(EmailAddresses.normalize("ISIL@EXAMPLE.COM")).isEqualTo("isil@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void noProviderSpecificRewriting() {
        assertThat(EmailAddresses.normalize("Ali.Veli+test@Gmail.com")).isEqualTo("ali.veli+test@gmail.com");
    }

    @ParameterizedTest(name = "invalid: [{0}]")
    @ValueSource(strings = {"", "ali", "ali@", "@example.com", "ali veli@example.com", "a@b@c"})
    void rejectsInvalidAddresses(String email) {
        assertThat(EmailAddresses.isValid(EmailAddresses.normalize(email))).isFalse();
    }

    @Test
    void acceptsUpTo254Characters() {
        String max = "a".repeat(242) + "@example.com";
        assertThat(max).hasSize(254);
        assertThat(EmailAddresses.isValid(max)).isTrue();
        assertThat(EmailAddresses.isValid("a" + max)).isFalse();
    }
}
