package com.aiexpense.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the full application and a real PostgreSQL 16 database.
 *
 * <p>The database comes from TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD:
 * GitHub Actions provides a PostgreSQL service container, Claude Code Cloud uses
 * {@code scripts/dev-db.sh}. When the variables are missing the test FAILS with a clear message;
 * it is never skipped silently (CLAUDE.md, test rules). Docker/Testcontainers is not used.
 */
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
public abstract class DatabaseIntegrationTest {

    static final String URL = "TEST_DB_URL";
    static final String USERNAME = "TEST_DB_USERNAME";
    static final String PASSWORD = "TEST_DB_PASSWORD";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnv(URL));
        registry.add("spring.datasource.username", () -> requiredEnv(USERNAME));
        registry.add("spring.datasource.password", () -> requiredEnv(PASSWORD));
        // A random signing key per test run; never a committed secret.
        registry.add("app.jwt.secret", DatabaseIntegrationTest::randomSecret);
    }

    static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set. Database tests need a PostgreSQL 16 database: "
                    + "run 'eval \"$(scripts/dev-db.sh env)\"' (Claude Code Cloud) or rely on GitHub Actions. "
                    + "To run only the tests without a database, use './gradlew test -PnoDb' and report "
                    + "the database tests as not run.");
        }
        return value;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
