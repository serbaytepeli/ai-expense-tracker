package com.aiexpense.auth;

import static com.aiexpense.support.AuthTestSupport.PASSWORD;
import static com.aiexpense.support.AuthTestSupport.loginJson;
import static com.aiexpense.support.AuthTestSupport.postJson;
import static com.aiexpense.support.AuthTestSupport.registerJson;
import static com.aiexpense.support.AuthTestSupport.uniqueClientIp;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** FR-1 acceptance criteria for registration and login (docs/requirements.md, api-contract.md section 2). */
@Import(AuthTestSupport.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class RegistrationAndLoginApiTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(AuthTestSupport.START);
    }

    @Test
    void registerReturnsTokensAndProfile() throws Exception {
        String email = uniqueEmail();
        mvc.perform(postJson("/api/v1/auth/register", registerJson(email, PASSWORD, "  Ali  ", "Europe/Berlin"))
                        .with(uniqueClientIp()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Ali"))
                .andExpect(jsonPath("$.user.currency").value("TRY"))
                .andExpect(jsonPath("$.user.timeZone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.user.locale").value("tr-TR"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void missingOrInvalidTimeZoneFallsBackToIstanbul() throws Exception {
        for (String timeZone : new String[] {null, "", "Mars/Olympus", "+03:00"}) {
            mvc.perform(postJson("/api/v1/auth/register", registerJson(uniqueEmail(), PASSWORD, "Ali", timeZone))
                            .with(uniqueClientIp()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.timeZone").value("Europe/Istanbul"));
        }
    }

    @Test
    void storesOnlyABcryptHashAndTokenHashes() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.Tokens tokens = AuthTestSupport.register(mvc, email);

        String hash = jdbc.sql("SELECT password_hash FROM users WHERE email = :e").param("e", email)
                .query(String.class).single();
        assertThat(hash).startsWith("$2a$12$").hasSize(60).doesNotContain(PASSWORD);

        List<String> tokenHashes = jdbc.sql("""
                SELECT rt.token_hash FROM refresh_tokens rt JOIN auth_sessions s ON s.id = rt.session_id
                JOIN users u ON u.id = s.user_id WHERE u.email = :e
                """).param("e", email).query(String.class).list();
        assertThat(tokenHashes).containsExactly(sha256(tokens.refreshToken()));
        assertThat(tokenHashes.getFirst()).isNotEqualTo(tokens.refreshToken());
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCaseAndSpaces() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.register(mvc, email);

        mvc.perform(postJson("/api/v1/auth/register", registerJson("  " + email.toUpperCase() + " ", PASSWORD, "Ali", null))
                        .with(uniqueClientIp()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", email)
                .query(Integer.class).single()).isOne();
    }

    @Test
    void concurrentRegistrationsWithTheSameEmailCreateOneUser() throws Exception {
        String email = uniqueEmail();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mvc.perform(postJson("/api/v1/auth/register", registerJson(email, PASSWORD, "Ali", null))
                    .with(uniqueClientIp())).andReturn().getResponse().getStatus();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", email)
                .query(Integer.class).single()).isOne();
    }

    @Test
    void invalidRegistrationsAreRejectedWithTheFieldName() throws Exception {
        assertValidationError(registerJson("not-an-email" + AuthTestSupport.TEST_DOMAIN.replace("@", "."), PASSWORD, "Ali", null), "email");
        assertValidationError(registerJson(uniqueEmail(), "short", "Ali", null), "password");
        assertValidationError(registerJson(uniqueEmail(), "x".repeat(73), "Ali", null), "password");
        // 36 characters but 72+ bytes in UTF-8 (BCrypt would silently truncate it).
        assertValidationError(registerJson(uniqueEmail(), "ğ".repeat(37), "Ali", null), "password");
        assertValidationError(registerJson(uniqueEmail(), PASSWORD, "   ", null), "displayName");
        assertValidationError(registerJson(uniqueEmail(), PASSWORD, "x".repeat(61), null), "displayName");
        assertValidationError("{\"email\":\"" + uniqueEmail() + "\"}", "displayName");

        mvc.perform(postJson("/api/v1/auth/register", "{not json").with(uniqueClientIp()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email LIKE :p")
                .param("p", "%" + AuthTestSupport.TEST_DOMAIN).query(Integer.class).single()).isZero();
    }

    @Test
    void loginNormalizesTheEmail() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.register(mvc, email);

        mvc.perform(postJson("/api/v1/auth/login", loginJson("  " + email.toUpperCase() + "  ", PASSWORD))
                        .with(uniqueClientIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void registrationWithMixedCaseIsLoginWithLowerCase() throws Exception {
        String local = "Ali-" + System.nanoTime();
        mvc.perform(postJson("/api/v1/auth/register",
                        registerJson(" " + local + AuthTestSupport.TEST_DOMAIN.toUpperCase() + " ", PASSWORD, "Ali", null))
                        .with(uniqueClientIp()))
                .andExpect(status().isCreated());

        mvc.perform(postJson("/api/v1/auth/login", loginJson(local.toLowerCase() + AuthTestSupport.TEST_DOMAIN, PASSWORD))
                        .with(uniqueClientIp()))
                .andExpect(status().isOk());
    }

    @Test
    void eachLoginStartsANewSession() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.register(mvc, email);
        AuthTestSupport.login(mvc, email);
        AuthTestSupport.login(mvc, email);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id
                WHERE u.email = :e AND s.revoked_at IS NULL
                """).param("e", email).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.register(mvc, email);

        JsonNode wrongPassword = loginProblem(email, "wrong-password-123");
        JsonNode unknownEmail = loginProblem(uniqueEmail(), PASSWORD);
        JsonNode tooLong = loginProblem(email, "x".repeat(100));

        for (JsonNode problem : List.of(wrongPassword, unknownEmail, tooLong)) {
            assertThat(problem.get("status").asInt()).isEqualTo(401);
            assertThat(problem.get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(problem.get("detail").asString()).isEqualTo(wrongPassword.get("detail").asString());
        }
    }

    @Test
    void passwordsAndTokensNeverAppearInLogs(CapturedOutput output) throws Exception {
        String email = uniqueEmail();
        String password = "very-secret-pass-" + System.nanoTime();
        MvcResult registered = mvc.perform(postJson("/api/v1/auth/register", registerJson(email, password, "Ali", null))
                .with(uniqueClientIp())).andExpect(status().isCreated()).andReturn();
        JsonNode body = AuthTestSupport.json(registered);
        mvc.perform(postJson("/api/v1/auth/login", loginJson(email, "wrong-" + password)).with(uniqueClientIp()))
                .andExpect(status().isUnauthorized());
        MvcResult refreshed = AuthTestSupport.refresh(mvc, body.get("refreshToken").asString());

        String logs = output.getAll();
        assertThat(logs).contains("User registered");
        assertThat(logs).doesNotContain(password)
                .doesNotContain(body.get("accessToken").asString())
                .doesNotContain(body.get("refreshToken").asString())
                .doesNotContain(AuthTestSupport.json(refreshed).get("refreshToken").asString());
    }

    private void assertValidationError(String json, String field) throws Exception {
        mvc.perform(postJson("/api/v1/auth/register", json).with(uniqueClientIp()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem(field)));
    }

    private JsonNode loginProblem(String email, String password) throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/login", loginJson(email, password)).with(uniqueClientIp()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();
        return AuthTestSupport.json(result);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
