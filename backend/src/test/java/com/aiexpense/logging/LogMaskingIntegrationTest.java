package com.aiexpense.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexpense.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** End to end: the JSON log output of the running application masks secrets. */
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingIntegrationTest extends DatabaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LogMaskingIntegrationTest.class);

    @Test
    void secretsAreMaskedInJsonLogs(CapturedOutput output) {
        // Made-up values for the test.
        log.warn("login attempt password=fake-password-123 header Authorization: Bearer fake.token.value");

        String lastLine = output.getOut().lines()
                .filter(line -> line.contains("login attempt"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(lastLine).startsWith("{")
                .contains("password=***")
                .contains("Bearer ***")
                .doesNotContain("fake-password-123")
                .doesNotContain("fake.token.value");
    }
}
